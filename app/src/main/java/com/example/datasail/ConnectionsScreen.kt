package com.example.datasail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.datasail.core.*
import java.util.UUID

internal fun SavedConnection.fieldErrors(): Map<String, String> = buildMap {
    if (name.isBlank() || name.length > 80) put("name", "填写 1–80 字连接名称")
    if (kind == DatabaseKind.SQLITE) {
        if (!database.matches(Regex("[a-f0-9]{64}"))) put("database", "请选择 SQLite 文件")
    } else {
        if (!host.trim().matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) || host.trim().length > 253) put("host", "填写 DNS 名称或 IPv4，不含协议或端口")
        if (port.toIntOrNull() !in 1..65535) put("port", "端口为 1–65535")
        if (username.isBlank() || username.length > 128) put("username", "请填写用户名")
        if (kind.isDocument && (authDatabase.trim().length !in 1..128 || !authDatabase.trim().all { it.isLetterOrDigit() || it == '_' || it == '-' })) put("authDatabase", "填写认证数据库，通常为 admin")
        if (kind.isSearch) {
            if (!SearchPolicy.validScope(database.trim())) put("database", "填写小写索引名或范围，如 logs-*")
            if (username.contains(':') || username.any { it < ' ' }) put("username", "Basic 用户名不可含冒号或控制字符")
        } else if (kind.isKeyValue) {
            if (!database.matches(Regex("0|[1-9][0-9]{0,4}")) || database.toIntOrNull() !in 0..65535) put("database", "填写数据库编号，通常为 0")
        } else if (database.trim().length !in 1..128 || !database.trim().all { it.isLetterOrDigit() || it == '_' || it == '-' }) put("database", "填写库名，仅支持字母、数字、下划线和连字符")
    }
}

// Metadata names are identities, not editable input: never normalize a scanned name into another target.
internal fun SavedConnection.canSelectDiscoveredDatabase(name: String): Boolean =
    name == name.trim() && copy(database = name).fieldErrors()["database"] == null

internal fun SavedConnection.discoveryErrors(hint: String): Map<String, String> =
    copy(database = DiscoveryDefaults.database(kind, hint)).fieldErrors()

@Composable
internal fun Connections(model: WorkspaceViewModel, state: WorkspaceUiState) {
    val saved by model.saved.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val pendingCertificate by model.pendingCertificate.collectAsStateWithLifecycle()
    var fingerprintChecked by remember { mutableStateOf(false) }
    LaunchedEffect(pendingCertificate) { fingerprintChecked = false }
    var discoveryHint by remember { mutableStateOf("") }
    var databaseSearch by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf("list") }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun dismissInput() { focus.clearFocus(); keyboard?.hide() }
    val stageLocation = remember { BringIntoViewRequester() }
    LaunchedEffect(stage) { dismissInput(); stageLocation.bringIntoView() }
    val securityLocation = remember { BringIntoViewRequester() }
    LaunchedEffect(discovery.tlsFailure) { if (discovery.tlsFailure) securityLocation.bringIntoView() }
    var draft by remember { mutableStateOf(SavedConnection()) }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(false) }
    var passwordRequest by remember { mutableIntStateOf(0) }
    var authorized by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedConnection?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { passwordRequest++; password = ""; authorized = false; model.cancelDiscovery() } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer); passwordRequest++; password = ""; model.cancelDiscovery() }
    }
    // A delayed file-picker result must not modify a different draft/connection.
    var importTarget by remember { mutableStateOf<Pair<String, DatabaseKind>?>(null) }
    val caPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = importTarget
        uri?.let { model.importCa(it) { hash -> if (target == (draft.id to draft.kind)) { draft = draft.copy(ca = hash, certificateTrust = null); authorized = false; model.cancelDiscovery() } } }
    }
    val sqlitePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = importTarget
        uri?.let { model.importSqlite(it) { id -> if (target == (draft.id to draft.kind)) { draft = draft.copy(database = id); authorized = false } } }
    }
    fun edit(connection: SavedConnection) {
        model.cancelDiscovery(); discoveryHint = ""; databaseSearch = ""; draft = connection; password = ""; rememberPassword = false
        authorized = false; advanced = false; stage = "form"
        val ticket = ++passwordRequest
        if (connection.kind != DatabaseKind.SQLITE) model.loadPassword(connection) { secret ->
            if (ticket == passwordRequest && stage == "form" && draft == connection && secret != null) {
                password = String(secret); rememberPassword = true
            }
        }
    }
    Spacer(Modifier.height(1.dp).bringIntoViewRequester(stageLocation))
    if (stage == "list") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("我的连接", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { stage = "type"; search = ""; category = "全部" }, enabled = !state.busy) { Text("新建连接") }
        }
        Text("${DatabaseKind.entries.size} 种已接入引擎 · 本机连接管理 · 可选择加密保存密码。", style = MaterialTheme.typography.bodySmall)
        if (saved.isEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("从第一个数据库开始", style = MaterialTheme.typography.titleMedium)
                Text("新建连接，选择数据库类型；也可导入 SQLite 文件。")
            } }
        } else {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜索连接名称、数据库或主机") }, singleLine = true)
            val matches = saved.filter { listOf(it.name, it.kind.label, it.host, it.database).any { value -> value.contains(search, true) } }
            if (matches.isEmpty()) Text("没有匹配的连接")
            matches.forEach { connection -> OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EngineBadge(connection.kind)
                        Column(Modifier.weight(1f)) {
                            Text(connection.name, style = MaterialTheme.typography.titleMedium)
                            Text(connection.kind.connectionCategory, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("${connection.kind.label} · ${if (connection.production) "生产 · 只读" else "测试"}")
                    Text(if (connection.kind == DatabaseKind.SQLITE) "本机只读文件副本" else "${connection.host}:${connection.port} / ${connection.database}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = !state.busy, onClick = { edit(connection) }) { Text("打开连接") }
                        TextButton(enabled = !state.busy, onClick = { edit(connection.copy(id = UUID.randomUUID().toString(), certificateTrust = null, name = (connection.name.take(77) + " 副本"))) }) { Text("复制") }
                        TextButton(enabled = !state.busy, onClick = { deleting = connection }) { Text("删除") }
                    }
                }
            } }
        }
    } else if (stage == "type") {
        TextButton(onClick = { stage = "list"; search = "" }) { Text("返回我的连接") }
        Text("选择数据库", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜索可连接的数据库") }, singleLine = true)
        Text("选择引擎，再配置连接。仅展示已接入的数据库。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "关系型", "键值", "文档", "搜索", "本地文件").forEach { item ->
                FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item) })
            }
        }
        val kinds = DatabaseKind.entries.filter { it.label.contains(search, true) && (category == "全部" || it.connectionCategory == category) }
        if (kinds.isEmpty()) Text("没有匹配的数据库，请更换分类或关键词。")
        EngineGrid(kinds) { kind ->
            edit(SavedConnection(kind = kind, name = kind.label, production = settings.defaultProduction, port = kind.defaultPort.toString(),
                database = if (kind.isKeyValue) "0" else "", username = if (kind.isKeyValue) "default" else ""))
        }
    } else {
        val errors = draft.fieldErrors()
        fun change(value: SavedConnection) { passwordRequest++; model.cancelDiscovery(); draft = value.withoutMovedTrust(); authorized = false }
        TextButton(enabled = !state.busy, onClick = { stage = "list"; password = ""; authorized = false; search = ""; model.cancelDiscovery() }) { Text("返回我的连接") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EngineBadge(draft.kind)
            Column { Text("配置 ${draft.kind.label}", style = MaterialTheme.typography.titleLarge)
                Text("服务器 → 扫描目标 → 连接", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ConnectionSection("01", "连接信息", "连接名称仅用于本机识别")
        ConnectionField("连接名称", draft.name, errors["name"]) { change(draft.copy(name = it)) }
        if (draft.kind == DatabaseKind.SQLITE) {
            Text("选择已导出的完整文件（≤64 MiB、非 WAL），保存只读副本。")
            OutlinedButton(onClick = { importTarget = draft.id to draft.kind; sqlitePicker.launch("*/*") }) { Text("选择 SQLite 文件") }
            Text(errors["database"] ?: "文件已选择")
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionField("服务器 DNS / IPv4", draft.host, errors["host"], Modifier.weight(2f)) { change(draft.copy(host = it)); password = "" }
                ConnectionField("端口", draft.port, errors["port"], Modifier.weight(1f), KeyboardType.Number) { change(draft.copy(port = it)); password = "" }
            }
            if (draft.kind.isSearch) Text("HTTPS · Basic 认证 · 仅浏览指定范围的普通索引；账号需具备读取、索引监控和映射查看权限。", style = MaterialTheme.typography.bodySmall)
            if (draft.kind.isDocument) {
                ConnectionField("认证数据库", draft.authDatabase, errors["authDatabase"]) { change(draft.copy(authDatabase = it)); password = "" }
                Text("单个服务器直连 · SCRAM-SHA-256 · 只读集合与文档", style = MaterialTheme.typography.bodySmall)
            }
            ConnectionField(if (draft.kind.isKeyValue) "ACL 用户名" else "用户名", draft.username, errors["username"]) { change(draft.copy(username = it)); password = "" }
            OutlinedTextField(password, { passwordRequest++; model.cancelDiscovery(); password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberPassword, { passwordRequest++; rememberPassword = it })
                Text("在此设备保存密码", Modifier.weight(1f))
                TextButton(onClick = { passwordRequest++; password = ""; rememberPassword = false; model.forgetPassword(draft.id) }) { Text("忘记密码") }
            }
            Text("保存配置或连接时生效，密码由 Android Keystore 加密。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row { Checkbox(draft.production, { change(draft.copy(production = it)) }); Text("生产环境（只读）") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(authorized, { authorized = it; if (!it) model.cancelDiscovery() }, modifier = Modifier.testTag("connection-authorization")); Text("我已获准访问上述数据库") }
        if (draft.kind != DatabaseKind.SQLITE) {
            Card(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(securityLocation),
                colors = CardDefaults.cardColors(containerColor = if (discovery.tlsFailure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (discovery.tlsFailure) "需要确认服务器证书" else "连接安全 · TLS 加密", style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        draft.certificateTrust != null -> "已确认服务器证书 · 此连接下次可直接扫描"
                        draft.ca != null -> "自选 CA · 验证服务器身份"
                        else -> "系统 CA · 首次连接自签名服务器，可直接在这里确认其证书。"
                    }, style = MaterialTheme.typography.bodyMedium)
                    if (discovery.tlsFailure) Text(discovery.message, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = draft.discoveryErrors(discoveryHint).isEmpty() && authorized && !state.busy && !discovery.busy,
                        onClick = { dismissInput(); model.inspectCertificate(draft, discoveryHint) }) { Text("获取并核对服务器证书") }
                    if (!authorized) Text("请先勾选访问授权；获取证书无需密码。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起证书详情" else "证书详情与 CA 设置") }
                    if (advanced) {
                        Text(draft.certificateTrust?.let { "服务器证书 SHA-256：${it.sha256}" } ?: draft.ca?.let { "CA SHA-256：$it" } ?: "使用系统信任的 CA。")
                        TextButton(onClick = { importTarget = draft.id to draft.kind; caPicker.launch("*/*") }) { Text("选择 CA 证书") }
                        TextButton(onClick = { change(draft.copy(ca = null, certificateTrust = null)) }) { Text("恢复系统 CA") }
                        Text("恢复后请保存配置。修改服务器、端口或复制连接，需要重新确认。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (draft.kind != DatabaseKind.SQLITE) {
            ConnectionSection("02", "发现数据库", "扫描当前账号可以访问的目标")
            if (draft.kind == DatabaseKind.POSTGRESQL || draft.kind.isSearch) {
                ConnectionField(if (draft.kind.isSearch) "扫描索引范围（默认 *）" else "扫描入口库（默认 postgres）", discoveryHint, draft.discoveryErrors(discoveryHint)["database"]) {
                    model.cancelDiscovery(); discoveryHint = it
                }
                if (draft.kind == DatabaseKind.POSTGRESQL) Text("PostgreSQL 需先登录一个可访问的库才能扫描；若 postgres 不可用，请填写已知库名。", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = draft.discoveryErrors(discoveryHint).isEmpty() && authorized && password.isNotEmpty() && !state.busy && !discovery.busy,
                    onClick = { dismissInput(); databaseSearch = ""; model.discover(draft, discoveryHint, password.toCharArray()) }) {
                    Text(if (discovery.busy) "扫描中…" else "连接服务器并扫描")
                }
                if (discovery.busy) TextButton(onClick = model::cancelDiscovery) { Text("取消扫描") }
            }
            if (discovery.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (discovery.message.isNotEmpty() && !discovery.tlsFailure) Text(discovery.message, style = MaterialTheme.typography.bodySmall)
            ConnectionSection("03", "选择数据库", "选择扫描结果，或手动填写已知目标")
            discovery.result?.let { result ->
                Text("发现 ${result.names.size} 个目标", style = MaterialTheme.typography.titleSmall)
                Text(result.note, style = MaterialTheme.typography.bodySmall)
                if (result.limited) Text("仅显示前 200 项，可手动填写未显示的目标。")
                if (result.names.isEmpty()) Text("未发现可见目标；空列表不表示服务器没有数据库。")
                else {
                    ConnectionField("筛选扫描结果", databaseSearch, null) { databaseSearch = it }
                    val matches = result.names.filter { it.contains(databaseSearch, ignoreCase = true) }
                    if (matches.isEmpty()) Text("没有匹配的目标")
                    matches.forEach { name ->
                        val selectable = draft.canSelectDiscoveredDatabase(name)
                        DatabaseTargetRow(name, draft.database == name, selectable && !state.busy && !discovery.busy, selectable) {
                            draft = draft.copy(database = name)
                        }
                    }
                }
            }
            Text("也可手动填写已知目标；未保存的密码离开页面即清除。", style = MaterialTheme.typography.bodySmall)
            ConnectionField(if (draft.kind.isSearch) "索引范围（如 logs-*）" else if (draft.kind.isKeyValue) "数据库编号" else "数据库", draft.database, errors["database"], keyboard = if (draft.kind.isKeyValue) KeyboardType.Number else KeyboardType.Text) { draft = draft.copy(database = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = (if (draft.kind == DatabaseKind.SQLITE) errors.isEmpty() else draft.discoveryErrors(discoveryHint).isEmpty()) && !state.busy && !discovery.busy, onClick = { model.saveLogin(draft, password.toCharArray(), rememberPassword && draft.kind != DatabaseKind.SQLITE) }) { Text("保存配置") }
            Button(enabled = errors.isEmpty() && authorized && !state.busy && !discovery.busy && (draft.kind == DatabaseKind.SQLITE || password.isNotEmpty()), onClick = {
                dismissInput()
                val secret = password.toCharArray(); model.saveLogin(draft, password.toCharArray(), rememberPassword && draft.kind != DatabaseKind.SQLITE, announce = false); passwordRequest++; password = ""; model.connect(draft, secret)
            }) { Text(if (state.busy) "连接中…" else "连接数据库") }
        }
    }
    pendingCertificate?.takeIf { it.connection == draft }?.let { pending ->
        val cert = pending.preview
        AlertDialog(onDismissRequest = model::cancelDiscovery, title = { Text("确认服务器证书") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${draft.kind.label} · ${draft.host}:${draft.port}")
                if (draft.certificateTrust != null && draft.certificateTrust!!.sha256 != cert.sha256) Text("证书已变化，请先向管理员确认更换原因。")
                Text("SHA-256：${cert.sha256}")
                Text("主体：${cert.subject}\n签发者：${cert.issuer}")
                Text("有效期：${java.util.Date(cert.notBefore)} — ${java.util.Date(cert.notAfter)}")
                Text("确认后以此证书指纹替代 CA 和地址匹配校验，保持 TLS 加密。首次获取的证书可能来自冒充服务器，须通过管理员等独立渠道核对指纹。")
                if (!cert.isCurrent()) Text("证书已过期或尚未生效，不能信任。请检查设备时间或更新服务器证书。")
                Row { Checkbox(fingerprintChecked, { fingerprintChecked = it }); Text("我已独立核对证书指纹") }
            } },
            confirmButton = { TextButton(enabled = fingerprintChecked && cert.isCurrent(), onClick = {
                model.confirmCertificate(draft)?.let { draft = it }
            }) { Text("信任并保存到此连接") } },
            dismissButton = { TextButton(onClick = model::cancelDiscovery) { Text("取消") } })
    }
    deleting?.let { connection -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除连接配置？") },
        text = { Text("将从本机移除“${connection.name}”的连接配置，数据库中的数据不受影响。") },
        confirmButton = { TextButton(onClick = { model.delete(connection.id); deleting = null }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun ConnectionField(label: String, value: String, error: String?, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    OutlinedTextField(value, change, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        isError = value.isNotEmpty() && error != null, supportingText = if (value.isNotEmpty() && error != null) ({ Text(error) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
