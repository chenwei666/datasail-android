package com.example.datasail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@Composable
internal fun Connections(model: WorkspaceViewModel, state: WorkspaceUiState) {
    val saved by model.saved.collectAsStateWithLifecycle()
    var stage by remember { mutableStateOf("list") }
    var search by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(SavedConnection()) }
    var password by remember { mutableStateOf("") }
    var authorized by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedConnection?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { password = ""; authorized = false } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer); password = "" }
    }
    // A delayed file-picker result must not modify a different draft/connection.
    var importTarget by remember { mutableStateOf<Pair<String, DatabaseKind>?>(null) }
    val caPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = importTarget
        uri?.let { model.importCa(it) { hash -> if (target == (draft.id to draft.kind)) { draft = draft.copy(ca = hash); authorized = false } } }
    }
    val sqlitePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = importTarget
        uri?.let { model.importSqlite(it) { id -> if (target == (draft.id to draft.kind)) { draft = draft.copy(database = id); authorized = false } } }
    }
    fun edit(connection: SavedConnection) { draft = connection; password = ""; authorized = false; advanced = false; stage = "form" }
    if (stage == "list") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("我的连接", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { stage = "type"; search = "" }, enabled = !state.busy) { Text("新建连接") }
        }
        Text("${DatabaseKind.entries.size} 种已接入引擎 · 连接配置只保存在本机，密码每次输入。", style = MaterialTheme.typography.bodySmall)
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
                    Text(connection.name, style = MaterialTheme.typography.titleMedium)
                    Text("${connection.kind.label} · ${if (connection.production) "生产 · 只读" else "测试"}")
                    Text(if (connection.kind == DatabaseKind.SQLITE) "本机只读文件副本" else "${connection.host}:${connection.port} / ${connection.database}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = !state.busy, onClick = { edit(connection) }) { Text("打开连接") }
                        TextButton(enabled = !state.busy, onClick = { edit(connection.copy(id = UUID.randomUUID().toString(), name = (connection.name.take(77) + " 副本"))) }) { Text("复制") }
                        TextButton(enabled = !state.busy, onClick = { deleting = connection }) { Text("删除") }
                    }
                }
            } }
        }
    } else if (stage == "type") {
        TextButton(onClick = { stage = "list"; search = "" }) { Text("返回我的连接") }
        Text("选择数据库", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜索可连接的数据库") }, singleLine = true)
        val targets = DatabaseCatalog.targets.filter { it.connectionKind != null && it.name.contains(search, true) }
        if (targets.isEmpty()) Text("此数据库尚未接入。完整开发范围可在设置中查看。")
        targets.forEach { target -> OutlinedCard(onClick = {
            val kind = target.connectionKind!!
            edit(SavedConnection(kind = kind, name = kind.label, port = kind.defaultPort.toString(),
                database = if (kind.isKeyValue) "0" else "", username = if (kind.isKeyValue) "default" else ""))
        }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) { Text(target.name, style = MaterialTheme.typography.titleMedium); Text(target.capability, style = MaterialTheme.typography.bodySmall) }
        } }
    } else {
        val errors = draft.fieldErrors()
        fun change(value: SavedConnection) { draft = value; authorized = false }
        TextButton(enabled = !state.busy, onClick = { stage = "list"; password = ""; authorized = false; search = "" }) { Text("返回我的连接") }
        Text("配置 ${draft.kind.label}", style = MaterialTheme.typography.titleLarge)
        Text(DatabaseCatalog.targets.first { it.connectionKind == draft.kind }.capability, style = MaterialTheme.typography.bodySmall)
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
            ConnectionField(if (draft.kind.isSearch) "索引范围（如 logs-*）" else if (draft.kind.isKeyValue) "数据库编号" else "数据库", draft.database, errors["database"], keyboard = if (draft.kind.isKeyValue) KeyboardType.Number else KeyboardType.Text) { change(draft.copy(database = it)) }
            if (draft.kind.isSearch) Text("HTTPS · Basic 认证 · 仅浏览指定范围的普通索引；账号需具备读取、索引监控和映射查看权限。", style = MaterialTheme.typography.bodySmall)
            if (draft.kind.isDocument) {
                ConnectionField("认证数据库", draft.authDatabase, errors["authDatabase"]) { change(draft.copy(authDatabase = it)); password = "" }
                Text("单个服务器直连 · SCRAM-SHA-256 · 只读集合与文档", style = MaterialTheme.typography.bodySmall)
            }
            ConnectionField(if (draft.kind.isKeyValue) "ACL 用户名" else "用户名", draft.username, errors["username"]) { change(draft.copy(username = it)); password = "" }
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码（不保存）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row { Checkbox(draft.production, { change(draft.copy(production = it)) }); Text("生产环境（只读）") }
            Text("连接使用 TLS，验证证书及服务器身份。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起证书设置" else "证书设置 · ${if (draft.ca == null) "系统 CA" else "自选 CA"}") }
            if (advanced) {
                Text(if (draft.ca == null) "使用系统信任的 CA。自签名服务器可导入专用 CA。" else "CA SHA-256：${draft.ca}")
                Text("请核对证书来源与指纹。")
                Row { TextButton(onClick = { importTarget = draft.id to draft.kind; caPicker.launch("*/*") }) { Text("选择 CA 证书") }
                    TextButton(onClick = { change(draft.copy(ca = null)) }) { Text("恢复系统 CA") } }
            }
        }
        Row { Checkbox(authorized, { authorized = it }); Text("我已获准访问上述数据库") }
        if (errors.isNotEmpty()) Text("请补全：" + errors.keys.joinToString("、") { when (it) { "name" -> "名称"; "host" -> "服务器"; "port" -> "端口"; "username" -> "用户名"; else -> "数据库" } }, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = errors.isEmpty() && !state.busy, onClick = { model.save(draft) }) { Text("保存配置") }
            Button(enabled = errors.isEmpty() && authorized && !state.busy && (draft.kind == DatabaseKind.SQLITE || password.isNotEmpty()), onClick = {
                val secret = password.toCharArray(); password = ""; model.connect(draft, secret)
            }) { Text(if (state.busy) "连接中…" else "连接数据库") }
        }
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
