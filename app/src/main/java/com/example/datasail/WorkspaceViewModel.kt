package com.example.datasail

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.datasail.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// All JDBC work is dispatched to one bounded worker; UI only holds immutable snapshots.
data class WorkspaceUiState(val busy: Boolean = false, val connected: Boolean = false,
    val target: String = "未连接", val catalog: String = "—", val schema: String = "—", val writable: Boolean = false,
    val kind: DatabaseKind = DatabaseKind.SQL_SERVER, val production: Boolean = false, val catalogs: List<String> = emptyList(), val schemas: List<String> = emptyList(),
    val tables: List<TableRef> = emptyList(), val result: ResultPage? = null, val preview: MutationPreview? = null,
    val message: String = "请选择已授权的数据库连接；默认只读。")
// Operation completion is authoritative even when a later step in that operation failed.
internal fun WorkspaceUiState.withSession(actual: SessionSnapshot): WorkspaceUiState {
    val targetChanged = catalog != actual.catalog || !actual.connected
    return copy(connected = actual.connected, catalog = actual.catalog ?: "—", writable = actual.writable,
        schema = if (targetChanged) "—" else schema,
        schemas = if (targetChanged) emptyList() else schemas,
        tables = if (targetChanged) emptyList() else tables,
        result = if (targetChanged) null else result,
        preview = null)
}
class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ConnectionStore(application)
    private val runner = WorkspaceRunner(localFactory = { profile -> SqliteBrowseSession(store.sqliteFile(profile.database), profile) })
    private val mutable = MutableStateFlow(WorkspaceUiState())
    val state = mutable.asStateFlow()
    private val connections = MutableStateFlow<List<SavedConnection>>(emptyList())
    val saved = connections.asStateFlow()
    private var storageValid = false
    private var epoch = 0L
    private var saving = false
    init { viewModelScope.launch { try { connections.value = withContext(Dispatchers.IO) { store.load() }; storageValid = true }
        catch (_: Exception) { message("连接配置读取失败；为保护原文件，本次禁止保存。") } } }
    fun message(text: String) { mutable.value = mutable.value.copy(message = text) }
    fun save(connection: SavedConnection) {
        if (!storageValid) { message("配置尚未成功读取，禁止覆盖原文件"); return }
        try { connection.profile(getApplication()); require(connection.name.isNotBlank() && connection.name.length <= 80) }
        catch (_: Exception) { message("请填写连接名称、有效服务器、端口、数据库和用户名"); return }
        persist(connections.value.filterNot { it.id == connection.id } + connection)
    }
    fun delete(id: String) { if (storageValid) persist(connections.value.filterNot { it.id == id }) }
    private fun persist(values: List<SavedConnection>) {
        if (saving) { message("配置正在保存，请稍后重试"); return }; saving = true
        viewModelScope.launch {
        try { withContext(Dispatchers.IO) { store.save(values) }; connections.value = values; message("连接元数据已保存；不保存密码") }
        catch (_: Exception) { message("保存失败，原配置保留；最多保存 100 个连接") }
        finally { saving = false }
    } }
    fun importSqlite(uri: Uri, done: (String) -> Unit) { viewModelScope.launch {
        try { val id = withContext(Dispatchers.IO) { store.importSqlite(uri) }; done(id); message("SQLite 一致性副本已导入；原文件保持不变") }
        catch (_: Exception) { message("导入失败：需要不超过 64 MiB、已导出为非 WAL 模式的完整 SQLite 文件") }
    } }
    fun importCa(uri: Uri, done: (String) -> Unit) { viewModelScope.launch {
        try { val fingerprint = withContext(Dispatchers.IO) { store.importCa(uri) }; done(fingerprint); message("CA 已导入，请核对 SHA-256 指纹；仅用于选择它的连接") }
        catch (_: Exception) { message("导入失败：需要有效的 X.509 CA 证书，文件不超过 64 KiB") }
    } }
    fun connect(connection: SavedConnection, password: CharArray) {
        if (mutable.value.busy) { password.fill('\u0000'); return }
        val profile = try { connection.profile(getApplication()) } catch (_: Exception) {
            password.fill('\u0000'); message("连接信息无效，请检查主机、端口、库名和用户名"); return
        }
        val credentials = Credentials(password); password.fill('\u0000')
        mutable.value = WorkspaceUiState(busy = true, target = if (profile.kind == DatabaseKind.SQLITE) "SQLite · ${connection.name}" else "${profile.kind.label} · ${profile.host}:${profile.port}", catalog = if (profile.kind == DatabaseKind.SQLITE) "main" else profile.database,
            kind = profile.kind, production = connection.production, message = if (profile.kind == DatabaseKind.SQLITE) "正在打开只读副本…" else "正在校验 TLS 并登录…")
        val ticket = ++epoch
        val accepted = runner.connect(profile, credentials) { result -> viewModelScope.launch {
            if (ticket != epoch) return@launch
            result.fold(onSuccess = {
                mutable.value = mutable.value.copy(busy = false, connected = true, message = "已连接，只读模式")
                execute({ s -> s.catalogs() to s.schemas() }) { value -> copy(catalogs = value.first, schemas = value.second) }
            }, onFailure = { failure(it) })
        } }
        if (!accepted) { mutable.value = mutable.value.copy(busy = false); message("上一操作仍在清理，请稍后手动重试") }
    }
    fun disconnect() {
        if (!mutable.value.connected && !mutable.value.busy) return
        epoch++
        runner.disconnect()
        mutable.value = WorkspaceUiState(message = "已请求取消并断开；未完成操作的执行结果未知，需核对。不会自动重试。")
    }
    fun switchCatalog(name: String) = execute({ s -> s.switchCatalog(name); s.schemas() }) {
        copy(catalog = name, schema = "—", schemas = it, tables = emptyList(), result = null, writable = false)
    }
    fun tables(schema: String) = execute({ it.tables(schema) }) { copy(schema = schema, tables = it, result = null) }
    fun page(table: TableRef, offset: Int = 0) = execute({ it.page(table, offset) }) { copy(result = it) }
    fun query(sql: String) = execute({ it.select(sql) }) { copy(result = it) }
    fun writable(enabled: Boolean) = execute({ it.setWritable(enabled) }) { copy(writable = enabled) }
    fun preview(kind: MutationKind, row: Int?, inputs: Map<String, CellInput>) = execute({ it.preview(kind, row, inputs) }) { copy(preview = it) }
    fun discardPreview() = execute({ it.discardPreview() }) { copy(preview = null) }
    fun confirm(token: String) = execute({ it.confirm(token) }) {
        copy(preview = null, result = null, connected = connected && it.status != MutationStatus.UNKNOWN,
            writable = writable && it.status != MutationStatus.UNKNOWN, message = it.message)
    }
    private fun <T> execute(operation: (SqlWorkspaceSession) -> T, update: WorkspaceUiState.(T) -> WorkspaceUiState) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, preview = null)
        val ticket = epoch
        val accepted = runner.execute({ reply -> viewModelScope.launch {
            if (ticket != epoch) return@launch
            mutable.value = mutable.value.withSession(reply.session)
            reply.result.fold(onSuccess = { mutable.value = mutable.value.copy(busy = false, message = "操作完成").update(it) }, onFailure = ::failure)
        } }, operation)
        if (!accepted) { mutable.value = mutable.value.copy(busy = false); message("上一操作仍在清理，请稍后重试") }
    }
    private fun failure(error: Throwable) {
        val safe = Failures.classify(error)
        val lost = safe.kind in setOf(ErrorKind.TIMEOUT, ErrorKind.NETWORK, ErrorKind.CANCELLED, ErrorKind.UNSUPPORTED)
        mutable.value = mutable.value.copy(busy = false, preview = null, result = null,
            connected = mutable.value.connected && !lost, writable = mutable.value.writable && !lost,
            message = if (error is WorkspaceRejected) error.explanation else safe.description)
    }
    override fun onCleared() { runner.close(); super.onCleared() }
}
