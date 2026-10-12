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
    val collections: List<String> = emptyList(), val documents: DocumentPage? = null,
    val keys: KeyScan? = null, val keyContent: KeyContent? = null,
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
    private val queryStore = QueryStore(application)
    private val queryList = MutableStateFlow<List<SavedQuery>>(emptyList())
    val queries = queryList.asStateFlow()
    private val queryText = MutableStateFlow("SELECT 1 AS 示例")
    val draftSql = queryText.asStateFlow()
    private var queryStorageValid = false
    private var querySaving = false
    private var exporting = false
    private val runner = WorkspaceRunner(localFactory = { profile -> SqliteBrowseSession(store.sqliteFile(profile.database), profile) })
    private val keyValueRunner = KeyValueRunner()
    private val documentRunner = DocumentRunner()
    private val mutable = MutableStateFlow(WorkspaceUiState())
    val state = mutable.asStateFlow()
    private val connections = MutableStateFlow<List<SavedConnection>>(emptyList())
    val saved = connections.asStateFlow()
    private var storageValid = false
    private var epoch = 0L
    private var saving = false
    init { viewModelScope.launch { try { connections.value = withContext(Dispatchers.IO) { store.load() }; storageValid = true }
        catch (_: Exception) { message("连接配置读取失败；为保护原文件，本次禁止保存。") } } }
    init { viewModelScope.launch {
        try { queryList.value = withContext(Dispatchers.IO) { queryStore.load() }; queryStorageValid = true }
        catch (_: Exception) { message("收藏读取失败，禁止覆盖原文件") }
    } }
    fun editSql(value: String) { if (value.length <= 16000) queryText.value = value }
    fun useQuery(query: SavedQuery): Boolean {
        if (!mutable.value.connected || mutable.value.kind != query.kind) { message("请先连接同类型数据库，再载入草稿"); return false }
        queryText.value = query.sql; message("已载入草稿，请检查当前目标后手动执行"); return true
    }
    fun saveQuery(name: String) {
        if (!mutable.value.connected || !mutable.value.kind.isSql) return
        val query = SavedQuery(name = name.trim(), kind = mutable.value.kind, sql = queryText.value)
        try { require(query.name.isNotBlank() && query.name.length <= 80); ReadQueryPolicy.validate(query.sql, query.kind) }
        catch (_: Exception) { message("请填写名称，且仅收藏本工作台支持的单条 SELECT"); return }
        persistQueries(queryList.value + query)
    }
    fun deleteQuery(id: String) = persistQueries(queryList.value.filterNot { it.id == id })
    private fun persistQueries(values: List<SavedQuery>) {
        if (!queryStorageValid || querySaving) { message("收藏尚未加载或正在保存，请稍后重试"); return }
        querySaving = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { queryStore.save(values) }; queryList.value = values; message("收藏已保存到本机") }
            catch (_: Exception) { message("收藏保存失败；最多 100 条，原文件保留") }
            finally { querySaving = false }
        }
    }
    fun exportPage(uri: Uri, page: ResultPage) {
        if (exporting) { message("正在导出，请稍后重试"); return }; exporting = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) {
                require(PageCsv.canExport(page))
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { PageCsv.write(page, it) }
            }; message("已导出已确认页面：${page.rows.size} 行；不是整表备份") }
            catch (_: Exception) { message("导出失败；目标文件可能不完整，请检查或删除") }
            finally { exporting = false }
        }
    }
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
        queryText.value = "SELECT 1 AS 示例"
        val ticket = ++epoch
        runner.disconnect(); keyValueRunner.disconnect(); documentRunner.disconnect()
        if (profile.kind.isDocument) {
            val accepted = documentRunner.connect(profile, credentials) { result -> viewModelScope.launch {
                if (ticket != epoch) return@launch
                result.fold(onSuccess = {
                    mutable.value = mutable.value.copy(busy = false, connected = true, message = "已连接，只读文档工作台")
                    collections()
                }, onFailure = ::failure)
            } }
            if (!accepted) { mutable.value = mutable.value.copy(busy = false); message("上一操作仍在清理，请稍后手动重试") }
            return
        }
        if (profile.kind.isKeyValue) {
            val accepted = keyValueRunner.connect(profile, credentials) { result -> viewModelScope.launch {
                if (ticket != epoch) return@launch
                result.fold(onSuccess = {
                    mutable.value = mutable.value.copy(busy = false, connected = true, message = "已连接，只读键值工作台")
                    scanKeys("0", "*")
                }, onFailure = ::failure)
            } }
            if (!accepted) { mutable.value = mutable.value.copy(busy = false); message("上一操作仍在清理，请稍后手动重试") }
            return
        }
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
        runner.disconnect(); keyValueRunner.disconnect(); documentRunner.disconnect()
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
    fun scanKeys(cursor: String, pattern: String) = executeKeyValue({ it.scan(cursor, pattern) }) { copy(keys = it, keyContent = null) }
    fun inspectKey(key: BinaryKey, cursor: String = "0") = executeKeyValue({ it.inspect(key, cursor) }) { copy(keyContent = it) }
    private fun <T> executeKeyValue(operation: (KeyValueSession) -> T, update: WorkspaceUiState.(T) -> WorkspaceUiState) {
        if (mutable.value.busy || !mutable.value.connected || !mutable.value.kind.isKeyValue) return
        mutable.value = mutable.value.copy(busy = true)
        val ticket = epoch
        val accepted = keyValueRunner.execute({ result, connected -> viewModelScope.launch {
            if (ticket != epoch) return@launch
            mutable.value = mutable.value.copy(busy = false, connected = connected)
            result.fold(onSuccess = { mutable.value = mutable.value.update(it).copy(message = "只读操作完成") }, onFailure = ::failure)
        } }, operation)
        if (!accepted) { mutable.value = mutable.value.copy(busy = false); message("上一操作仍在清理，请稍后重试") }
    }
    fun collections() = executeDocument({ it.collections() }) { copy(collections = it, documents = null) }
    fun findDocuments(collection: String, filter: String) = executeDocument({ it.find(collection, filter) }) { copy(documents = it) }
    fun nextDocuments() = executeDocument({ it.next() }) { copy(documents = it) }
    private fun <T> executeDocument(operation: (DocumentSession) -> T, update: WorkspaceUiState.(T) -> WorkspaceUiState) {
        if (mutable.value.busy || !mutable.value.connected || !mutable.value.kind.isDocument) return
        mutable.value = mutable.value.copy(busy = true)
        val ticket = epoch
        val accepted = documentRunner.execute({ result, connected -> viewModelScope.launch {
            if (ticket != epoch) return@launch
            mutable.value = mutable.value.copy(busy = false, connected = connected)
            result.fold(onSuccess = { mutable.value = mutable.value.update(it).copy(message = "只读文档操作完成") }, onFailure = ::failure)
        } }, operation)
        if (!accepted) { mutable.value = mutable.value.copy(busy = false); message("上一操作仍在清理，请稍后重试") }
    }
    private fun failure(error: Throwable) {
        val safe = Failures.classify(error)
        val lost = safe.kind in setOf(ErrorKind.TIMEOUT, ErrorKind.NETWORK, ErrorKind.CANCELLED, ErrorKind.UNSUPPORTED)
        mutable.value = mutable.value.copy(busy = false, preview = null, result = null, documents = null, keys = null, keyContent = null,
            connected = mutable.value.connected && !lost, writable = mutable.value.writable && !lost,
            message = if (error is WorkspaceRejected) error.explanation else safe.description)
    }
    override fun onCleared() { runner.close(); keyValueRunner.close(); documentRunner.close(); super.onCleared() }
}
