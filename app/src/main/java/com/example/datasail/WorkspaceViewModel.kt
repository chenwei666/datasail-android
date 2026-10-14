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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun discoveryFailureMessage(error: Throwable): String =
    if (Failures.classify(error).kind == ErrorKind.TLS)
        "TLS 校验失败，未取得列表。请点击下方按钮获取并核对服务器证书，或在证书详情中导入 CA。已信任的证书也可能已变更或过期。未降级为明文。"
    else (if (error is WorkspaceRejected) error.explanation else Failures.classify(error).description) +
        "。未取得列表；可检查扫描参数或权限，或手动填写已知目标。"

data class PendingCertificate(val connection: SavedConnection, val preview: CertificatePreview)

data class DiscoveryUiState(val busy: Boolean = false, val result: DiscoveredDatabases? = null, val message: String = "", val tlsFailure: Boolean = false)

data class IndexMapping(val index: String, val json: String)

// All JDBC work is dispatched to one bounded worker; UI only holds immutable snapshots.
data class WorkspaceUiState(val busy: Boolean = false, val connected: Boolean = false,
    val aiGeneration: Long = 0, val allowExternalAi: Boolean = false, val connectionKey: String = "", val connectionName: String = "", val target: String = "未连接", val catalog: String = "—", val schema: String = "—", val writable: Boolean = false,
    val kind: DatabaseKind = DatabaseKind.SQL_SERVER, val production: Boolean = false, val catalogs: List<String> = emptyList(), val schemas: List<String> = emptyList(),
    val indices: List<String> = emptyList(), val searchPage: SearchPage? = null, val mapping: IndexMapping? = null,
    val collections: List<String> = emptyList(), val documents: DocumentPage? = null,
    val keys: KeyScan? = null, val keyContent: KeyContent? = null,
    val resultStale: Boolean = false, val selectedSchema: TableSchema? = null, val navigationIssues: List<String> = emptyList(), val tables: List<TableRef> = emptyList(), val result: ResultPage? = null, val preview: MutationPreview? = null,
    val message: String = "请选择已授权的数据库连接；默认只读。")
// Operation completion is authoritative even when a later step in that operation failed.
internal fun WorkspaceUiState.withSession(actual: SessionSnapshot): WorkspaceUiState {
    val targetChanged = catalog != actual.catalog || !actual.connected
    return copy(connected = actual.connected, catalog = actual.catalog ?: "—", writable = actual.writable,
        schema = if (targetChanged) "—" else schema,
        schemas = if (targetChanged) emptyList() else schemas,
        tables = if (targetChanged) emptyList() else tables,
        result = if (targetChanged) null else result,
        selectedSchema = if (targetChanged) null else selectedSchema,
        preview = null)
}
class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ConnectionStore(application)
    private val passwords = PasswordVault(application)
    private val passwordOperations = Mutex()
    private val settingsStore = AppSettingsStore(application)
    private val preferences = MutableStateFlow(AppSettings())
    val settings = preferences.asStateFlow()
    private val passwordCountState = MutableStateFlow(0)
    val passwordCount = passwordCountState.asStateFlow()
    private var settingsReady = false
    private val settingsOperations = Mutex()
    private var persistedSettings = AppSettings()
    private val tableStore = TableFavoriteStore(application)
    private val tableList = MutableStateFlow<List<TableFavorite>>(emptyList())
    val favoriteTables = tableList.asStateFlow()
    private var tableStorageValid = false
    private var tableSaving = false
    private val queryStore = QueryStore(application)
    private val queryList = MutableStateFlow<List<SavedQuery>>(emptyList())
    val queries = queryList.asStateFlow()
    internal val memory = WorkspaceMemory()
    private var lastConnection: SavedConnection? = null
    private val queryText = MutableStateFlow("SELECT 1 AS 示例")
    val draftSql = queryText.asStateFlow()
    private var queryStorageValid = false
    private var querySaving = false
    private var exporting = false
    private val runner = WorkspaceRunner(localFactory = { profile -> SqliteBrowseSession(store.sqliteFile(profile.database), profile) })
    private val keyValueRunner = KeyValueRunner()
    private val documentRunner = DocumentRunner()
    private val searchRunner = SearchRunner()
    private val discoveryRunner = DiscoveryRunner()
    private val discovering = MutableStateFlow(DiscoveryUiState())
    val discovery = discovering.asStateFlow()
    private var discoveryEpoch = 0L
    private var certificateReview: CertificateReview? = null
    private val reviewing = MutableStateFlow<PendingCertificate?>(null)
    val pendingCertificate = reviewing.asStateFlow()
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
    init { viewModelScope.launch {
        try { preferences.value = withContext(Dispatchers.IO) { settingsStore.load() }; passwordCountState.value = withContext(Dispatchers.IO) { passwords.count() } }
        catch (_: Exception) { message("设置读取失败，使用本次默认值") }
        finally { persistedSettings = preferences.value; settingsReady = true }
    } }
    init { viewModelScope.launch {
        try { tableList.value = withContext(Dispatchers.IO) { tableStore.load() }; tableStorageValid = true }
        catch (_: Exception) { message("表收藏读取失败，禁止覆盖原文件") }
    } }
    fun toggleTableFavorite(table: TableRef) {
        val current = mutable.value
        if (!current.connected || table.catalog != current.catalog || table != current.result?.tableSchema?.table) return
        val old = tableList.value.find { it.matches(current.connectionKey, table) }
        persistTables(if (old != null) tableList.value - old else tableList.value + TableFavorite(current.connectionKey,
            current.connectionName, current.kind, table.catalog, table.schema, table.name, table.view))
    }
    fun removeTableFavorite(value: TableFavorite) = persistTables(tableList.value - value)
    private fun persistTables(values: List<TableFavorite>) {
        if (!tableStorageValid || tableSaving) { message("表收藏尚未加载或正在保存，请稍后重试"); return }
        tableSaving = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { tableStore.save(values) }; tableList.value = values; message("表收藏已保存到本机") }
            catch (_: Exception) { message("表收藏保存失败；最多 100 项，原文件保留") }
            finally { tableSaving = false }
        }
    }
    fun openTableFavorite(value: TableFavorite): Boolean {
        val current = mutable.value
        if (!current.connected || current.busy || current.connectionKey != value.connectionKey || current.kind != value.kind) {
            message("请先连接收藏对应的原连接：${value.connectionName}"); return false
        }
        execute({ session ->
            if (session.catalog != value.catalog) session.switchCatalog(value.catalog)
            val navigation = SqlNavigator.load(session, value.schema)
            val table = navigation.tables.singleOrNull { it.schema == value.schema && it.name == value.name && it.view == value.view }
                ?: throw WorkspaceRejected("收藏对象不存在或不可见，请刷新并检查 Schema / 权限。未打开其它同名表。")
            navigation to session.page(table)
        }) {
            memory.remember(connectionKey, it.second)
            navigation(it.first).copy(result = it.second, selectedSchema = it.second.tableSchema, message = "已打开收藏表")
        }
        return true
    }
    fun updateSettings(value: AppSettings) {
        if (!settingsReady) { message("设置正在加载，请稍后重试"); return }
        preferences.value = value
        viewModelScope.launch { settingsOperations.withLock {
            try { withContext(Dispatchers.IO) { settingsStore.save(value) }; persistedSettings = value }
            catch (_: Exception) {
                if (preferences.value == value) preferences.value = persistedSettings
                message("设置保存失败，已恢复上次保存值")
            }
        } }
    }
    fun loadPassword(connection: SavedConnection, done: (CharArray?) -> Unit) { viewModelScope.launch {
        var secret: CharArray? = null
        try { secret = withContext(Dispatchers.IO) { passwords.load(connection) }; done(secret) }
        catch (_: Exception) { message("已存密码无法解密，请重新输入并保存；原密文保留") }
        finally { secret?.fill('\u0000') }
    } }
    fun forgetPassword(id: String? = null) { viewModelScope.launch {
        try {
            passwordOperations.withLock { withContext(Dispatchers.IO) { if (id == null) passwords.clear() else passwords.delete(id) } }
            passwordCountState.value = withContext(Dispatchers.IO) { passwords.count() }
            message(if (id == null) "已清除全部保存的密码" else "已忘记此连接的密码")
        } catch (_: Exception) { message("密码清除失败，请重试") }
    } }
    fun saveLogin(connection: SavedConnection, password: CharArray, remember: Boolean, announce: Boolean = true) {
        if (!storageValid || saving) { password.fill('\u0000'); message("配置尚未加载或正在保存，请稍后重试"); return }
        val errors = if (connection.kind == DatabaseKind.SQLITE || connection.database.isNotBlank()) connection.fieldErrors() else connection.discoveryErrors("")
        if (errors.isNotEmpty() || (remember && password.isEmpty())) { password.fill('\u0000'); message("请检查连接信息与要保存的密码"); return }
        saving = true
        val values = connections.value.filterNot { it.id == connection.id } + connection
        viewModelScope.launch {
            try { passwordOperations.withLock {
                withContext(Dispatchers.IO) { store.save(values) }; connections.value = values
                try {
                    withContext(Dispatchers.IO) { if (remember) passwords.save(connection, password) else passwords.delete(connection.id) }
                    passwordCountState.value = withContext(Dispatchers.IO) { passwords.count() }
                    if (announce) message(if (remember) "连接及加密密码已保存到此设备" else "连接已保存，不保存密码")
                } catch (_: Exception) { message("连接配置已保存，但密码保存或移除失败，请重试") }
            } } catch (_: Exception) { message("配置保存失败，原配置保留") }
            finally { password.fill('\u0000'); saving = false }
        }
    }
    fun editSql(value: String) { if (value.length <= 16000) { queryText.value = value; memory.draft(mutable.value.connectionKey, value) } }
    fun clearWorkspaceMemory() { memory.clear(); message("已清除本次运行的工作状态与查询历史") }
    fun reconnect(needsPassword: () -> Unit) {
        val old = lastConnection ?: return
        val current = connections.value.firstOrNull { tableConnectionKey(it) == tableConnectionKey(old) } ?: old
        val location = memory.last(tableConnectionKey(current))
        connectSaved(if (current.kind.isSql && current.kind != DatabaseKind.SQLITE && location != null) current.copy(database = location.table.catalog) else current, needsPassword)
    }
    fun useQuery(query: SavedQuery): Boolean {
        if (!mutable.value.connected || mutable.value.kind != query.kind) { message("请先连接同类型数据库，再载入草稿"); return false }
        editSql(query.sql); message("已载入草稿，请检查当前目标后手动执行"); return true
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
    fun delete(id: String) { if (storageValid) { persist(connections.value.filterNot { it.id == id }); forgetPassword(id) } }
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
    fun cancelDiscovery() {
        certificateReview?.close(); certificateReview = null; reviewing.value = null
        discoveryEpoch++
        discoveryRunner.cancel()
        discovering.value = DiscoveryUiState()
    }
    fun inspectCertificate(connection: SavedConnection, hint: String) {
        if (mutable.value.busy || discovering.value.busy || !storageValid) return
        cancelDiscovery()
        val review = CertificateReview()
        val profile = try { connection.copy(database = DiscoveryDefaults.database(connection.kind, hint)).profile(getApplication(), review.token) }
        catch (_: Exception) { review.close(); discovering.value = DiscoveryUiState(message = "请检查服务器和账号信息"); return }
        certificateReview = review
        val ticket = discoveryEpoch
        discovering.value = DiscoveryUiState(busy = true, message = "正在获取服务器证书；不会登录数据库…")
        val accepted = discoveryRunner.scan(profile, Credentials(charArrayOf())) { result -> viewModelScope.launch {
            val preview = review.preview(); review.close()
            if (ticket != discoveryEpoch) return@launch
            certificateReview = null
            if (preview != null) {
                reviewing.value = PendingCertificate(connection, preview)
                discovering.value = DiscoveryUiState(message = "已取得证书，尚未信任或登录。请核对后确认。")
            } else discovering.value = DiscoveryUiState(message = "未取得证书。" +
                (result.exceptionOrNull()?.let { Failures.classify(it).description } ?: "服务器未提供可检查的 TLS 证书"))
        } }
        if (!accepted) { review.close(); certificateReview = null; discovering.value = DiscoveryUiState(message = "上一操作仍在清理，请稍后重试") }
    }
    fun confirmCertificate(connection: SavedConnection): SavedConnection? {
        val pending = reviewing.value ?: return null
        if (pending.connection != connection || !pending.preview.isCurrent() || !storageValid || saving) return null
        val trusted = connection.copy(ca = null, certificateTrust = SavedCertificateTrust(connection.id, connection.kind,
            connection.host.trim(), connection.port.toInt(), pending.preview.sha256, System.currentTimeMillis()))
        cancelDiscovery()
        persist(connections.value.filterNot { it.id == trusted.id } + trusted)
        discovering.value = DiscoveryUiState(message = "已选择此服务器证书；请手动扫描或连接。")
        return trusted
    }
    fun discover(connection: SavedConnection, hint: String, password: CharArray) {
        if (mutable.value.busy || discovering.value.busy) { password.fill('\u0000'); return }
        cancelDiscovery()
        val profile = try { connection.copy(database = DiscoveryDefaults.database(connection.kind, hint)).profile(getApplication()) }
        catch (_: Exception) { password.fill('\u0000'); discovering.value = DiscoveryUiState(message = "请检查服务器、账号和扫描参数"); return }
        val credentials = Credentials(password); password.fill('\u0000')
        val ticket = discoveryEpoch
        discovering.value = DiscoveryUiState(busy = true, message = "正在验证服务器并扫描…")
        val accepted = discoveryRunner.scan(profile, credentials) { result -> viewModelScope.launch {
            if (ticket != discoveryEpoch) return@launch
            result.fold(onSuccess = {
                discovering.value = DiscoveryUiState(result = it, message = "扫描完成，请选择目标后连接")
            }, onFailure = {
                discovering.value = DiscoveryUiState(message = discoveryFailureMessage(it), tlsFailure = Failures.classify(it).kind == ErrorKind.TLS)
            })
        } }
        if (!accepted) discovering.value = DiscoveryUiState(message = "上一扫描仍在清理，请稍后重试")
    }
    fun connectSaved(connection: SavedConnection, needsPassword: () -> Unit) {
        if (mutable.value.busy) return
        if (connection.fieldErrors().isNotEmpty()) { needsPassword(); message("请补全连接信息后连接"); return }
        cancelDiscovery()
        val ticket = ++epoch
        mutable.value = mutable.value.copy(busy = true, message = "正在读取此连接的已存凭据…")
        viewModelScope.launch {
            var secret: CharArray? = null
            try {
                secret = if (connection.kind == DatabaseKind.SQLITE) charArrayOf() else passwordOperations.withLock {
                    withContext(Dispatchers.IO) { passwords.load(connection) }
                }
                if (ticket != epoch) return@launch
                mutable.value = mutable.value.copy(busy = false)
                if (secret == null) { needsPassword(); message("此连接尚未保存密码，请填写后连接") }
                else connect(connection, secret)
            } catch (_: Exception) {
                if (ticket == epoch) { mutable.value = mutable.value.copy(busy = false); needsPassword(); message("已存密码无法读取，请重新填写") }
            } finally { secret?.fill('\u0000') }
        }
    }
    fun connect(connection: SavedConnection, password: CharArray) {
        cancelDiscovery()
        if (mutable.value.busy) { password.fill('\u0000'); return }
        val profile = try { connection.profile(getApplication()) } catch (_: Exception) {
            password.fill('\u0000'); message("连接信息无效，请检查主机、端口、库名和用户名"); return
        }
        val credentials = Credentials(password); password.fill('\u0000')
        memory.draft(mutable.value.connectionKey, queryText.value)
        lastConnection = connection
        mutable.value = WorkspaceUiState(busy = true, aiGeneration = epoch + 1, allowExternalAi = connection.allowExternalAi, connectionKey = tableConnectionKey(connection), connectionName = connection.name, target = if (profile.kind == DatabaseKind.SQLITE) "SQLite · ${connection.name}" else "${profile.kind.label} · ${profile.host}:${profile.port}", catalog = if (profile.kind == DatabaseKind.SQLITE) "main" else profile.database,
            kind = profile.kind, production = connection.production, message = if (profile.kind == DatabaseKind.SQLITE) "正在打开只读副本…" else "正在校验 TLS 并登录…")
        queryText.value = memory.draft(tableConnectionKey(connection))
        val ticket = ++epoch
        runner.disconnect(); keyValueRunner.disconnect(); documentRunner.disconnect(); searchRunner.disconnect()
        if (profile.kind.isSearch) {
            val accepted = searchRunner.connect(profile, credentials) { result -> viewModelScope.launch {
                if (ticket != epoch) return@launch
                result.fold(onSuccess = {
                    mutable.value = mutable.value.copy(busy = false, connected = true, message = "已连接，只读搜索工作台")
                    indices()
                }, onFailure = ::failure)
            } }
            if (!accepted) { mutable.value = mutable.value.copy(busy = false); message("上一操作仍在清理，请稍后手动重试") }
            return
        }
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
                restoreWorkspace()
            }, onFailure = { failure(it) })
        } }
        if (!accepted) { mutable.value = mutable.value.copy(busy = false); message("上一操作仍在清理，请稍后手动重试") }
    }
    fun disconnect() {
        cancelDiscovery()
        if (!mutable.value.connected && !mutable.value.busy) return
        epoch++
        runner.disconnect(); keyValueRunner.disconnect(); documentRunner.disconnect(); searchRunner.disconnect()
        val old = mutable.value
        memory.draft(old.connectionKey, queryText.value)
        mutable.value = WorkspaceUiState(connectionKey = old.connectionKey, connectionName = old.connectionName,
            message = if (old.busy) "已请求取消并断开；未完成操作的执行结果未知，需核对。不会自动重试。" else "已断开；本次运行的草稿与表视图已保留，可重新连接恢复。")
    }
    private fun WorkspaceUiState.navigation(value: SqlNavigation) = copy(
        catalogs = value.catalogs, schemas = value.schemas, schema = value.schema ?: "—",
        tables = value.tables, result = null, selectedSchema = null, resultStale = false, navigationIssues = value.issues,
        message = if (value.issues.isEmpty()) "已加载数据库对象，请点击表或视图查看数据" else "已连接，但部分对象列表未能加载，请查看数据库导航提示")
    fun refreshNavigation() {
        val preferred = mutable.value.schema.takeUnless { it == "—" }
        execute({ SqlNavigator.load(it, preferred) }) { navigation(it) }
    }
    fun switchCatalog(name: String) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(tables = emptyList(), schemas = emptyList(), schema = "—", result = null, selectedSchema = null, resultStale = false, navigationIssues = emptyList())
        execute({ s -> s.switchCatalog(name); SqlNavigator.load(s) }) { navigation(it) }
    }
    fun tables(schema: String) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(schema = schema, tables = emptyList(), result = null, selectedSchema = null, resultStale = false, navigationIssues = emptyList())
        execute({ SqlNavigator.load(it, schema) }) { navigation(it) }
    }
    private fun restoreWorkspace() {
        val location = memory.last(mutable.value.connectionKey)
        execute({ session ->
            val nav = SqlNavigator.load(session, location?.table?.schema)
            val table = nav.tables.firstOrNull { it == location?.table }
            val result = if (table != null) runCatching { session.page(table, location!!.offset, location.columnOffset, location.view) } else null
            result?.exceptionOrNull()?.let { error -> if (Failures.classify(error).kind in setOf(ErrorKind.NETWORK, ErrorKind.TIMEOUT, ErrorKind.CANCELLED, ErrorKind.UNSUPPORTED, ErrorKind.TLS) || session.isClosed()) throw error }
            Triple(nav, result?.getOrNull(), result?.exceptionOrNull())
        }) {
            val next = navigation(it.first)
            if (it.second != null) next.copy(result = it.second, selectedSchema = it.second!!.tableSchema, message = "已恢复上次表视图；当前只读")
            else if (it.third != null) {
                location?.let { old -> memory.forgetView(connectionKey, old.table) }
                next.copy(message = "已连接；上次表视图恢复失败，已清除该表的记忆条件，请重新选择表")
            } else next
        }
    }
    fun page(table: TableRef, offset: Int = 0, columnOffset: Int = 0, view: TableView = mutable.value.result?.takeIf { it.tableSchema?.table == table }?.view ?: memory.location(mutable.value.connectionKey, table)?.view ?: TableView()) = execute({ it.page(table, offset, columnOffset, view) }, onFailure = { error ->
        if (error is WorkspaceRejected || Failures.classify(error).kind == ErrorKind.SQL) memory.forgetView(mutable.value.connectionKey, table)
    }) {
        memory.remember(connectionKey, it)
        copy(result = it, selectedSchema = it.tableSchema, resultStale = false)
    }
    internal fun aiTableAction(scope: String, table: TableRef, action: AiTableAction, allowWrites: Boolean) {
        if (!mutable.value.connected || mutable.value.busy || mutable.value.resultStale || !aiAllowed(this) || aiScope(this) != scope || mutable.value.result?.tableSchema?.table != table) return
        execute({ AiTableActions.prepare(it, table, action, allowWrites) }) {
            memory.remember(connectionKey, it.page)
            copy(result = it.page, selectedSchema = it.page.tableSchema, resultStale = false, preview = it.preview, message = if (it.preview == null) "AI 查询完成：${it.page.rows.size} 行${if (it.page.hasMore) "（还有更多）" else ""}" else "AI 已生成单条写入预览；确认后才会提交")
        }
    }
    fun query(sql: String) {
        val started = android.os.SystemClock.elapsedRealtime()
        execute({ it.select(sql) }) {
            memory.record(connectionKey, QueryRun(sql, catalog, schema, it.rows.size, android.os.SystemClock.elapsedRealtime() - started))
            copy(result = it, resultStale = false)
        }
    }
    fun writable(enabled: Boolean) = execute({ it.setWritable(enabled) }) { copy(writable = enabled) }
    fun preview(kind: MutationKind, row: Int?, inputs: Map<String, CellInput>) {
        if (mutable.value.resultStale) { message("上次操作失败，请刷新数据后再编辑"); return }
        execute({ it.preview(kind, row, inputs) }) { copy(preview = it) }
    }
    fun discardPreview() = execute({ it.discardPreview() }) { copy(preview = null) }
    private data class ConfirmedPage(val mutation: MutationResult, val page: ResultPage?, val refreshFailed: Boolean)
    fun confirm(token: String) {
        if (mutable.value.resultStale || mutable.value.preview?.token != token) { message("确认已失效，请刷新并重新预览"); return }
        val original = mutable.value.result
        val schema = original?.tableSchema
        execute({ session ->
            val mutation = session.confirm(token)
            var refreshed: ResultPage? = null
            var failed = false
            if (mutation.status != MutationStatus.UNKNOWN && schema != null) {
                try { refreshed = session.page(schema.table, original.offset, original.columnOffset, original.view) }
                catch (_: Exception) { failed = true; runCatching { session.close() } }
            }
            ConfirmedPage(mutation, refreshed, failed)
        }) {
            it.page?.let { page -> memory.remember(connectionKey, page) }
            copy(preview = null, result = it.page, selectedSchema = it.page?.tableSchema ?: selectedSchema, resultStale = false, connected = connected && !it.refreshFailed && it.mutation.status != MutationStatus.UNKNOWN,
                writable = writable && !it.refreshFailed && it.mutation.status != MutationStatus.UNKNOWN,
                message = it.mutation.message + if (it.refreshFailed) "；刷新失败，已断开。请重新连接核对，不会重放修改。" else "")
        }
    }
    private fun <T> execute(operation: (SqlWorkspaceSession) -> T, onFailure: (Throwable) -> Unit = {}, update: WorkspaceUiState.(T) -> WorkspaceUiState) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, preview = null)
        val ticket = epoch
        val accepted = runner.execute({ reply -> viewModelScope.launch {
            if (ticket != epoch) return@launch
            mutable.value = mutable.value.withSession(reply.session)
            reply.result.fold(onSuccess = { mutable.value = mutable.value.copy(busy = false, message = "操作完成").update(it) }, onFailure = { onFailure(it); failure(it) })
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
    fun indices() = executeSearch({ it.indices() }) { copy(indices = it, searchPage = null, mapping = null) }
    fun search(index: String, query: String, offset: Int = 0) = executeSearch({ it.search(index, query, offset) }) { copy(searchPage = it, mapping = null) }
    fun mapping(index: String) = executeSearch({ it.mapping(index) }) { copy(mapping = IndexMapping(index, it), searchPage = null) }
    private fun <T> executeSearch(operation: (SearchSession) -> T, update: WorkspaceUiState.(T) -> WorkspaceUiState) {
        if (mutable.value.busy || !mutable.value.connected || !mutable.value.kind.isSearch) return
        mutable.value = mutable.value.copy(busy = true)
        val ticket = epoch
        val accepted = searchRunner.execute({ result, connected -> viewModelScope.launch {
            if (ticket != epoch) return@launch
            mutable.value = mutable.value.copy(busy = false, connected = connected)
            result.fold(onSuccess = { mutable.value = mutable.value.update(it).copy(message = "只读搜索操作完成") }, onFailure = ::failure)
        } }, operation)
        if (!accepted) { mutable.value = mutable.value.copy(busy = false); message("上一操作仍在清理，请稍后重试") }
    }
    private fun failure(error: Throwable) {
        mutable.value = mutable.value.afterFailure(error)
    }

    override fun onCleared() {
        certificateReview?.close(); discoveryRunner.close(); runner.close(); keyValueRunner.close(); documentRunner.close(); searchRunner.close(); super.onCleared() }
}
