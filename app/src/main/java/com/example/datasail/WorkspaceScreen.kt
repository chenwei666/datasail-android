package com.example.datasail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

@Composable
internal fun WorkspaceApp(model: WorkspaceViewModel, probe: ProbeViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf("连接") }
    Scaffold(bottomBar = { NavigationBar {
        listOf("连接", "工作台", "AI 助手", "收藏", "设置").forEach { title ->
            val available = title !in setOf("AI 助手", "收藏")
            NavigationBarItem(selected = page == title, onClick = { page = title }, enabled = available,
                icon = { Text(if (available) "●" else "—") }, label = { Text(title) })
        }
    } }) { padding -> Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("数舟", style = MaterialTheme.typography.headlineMedium)
            Text("DataSail · V0.3.0 · 多数据库实验性工作台")
            Text("${state.target} / ${state.catalog} / ${state.schema}\n${if (state.production) "生产" else "测试"} · ${if (state.writable) "可写" else "只读"} · ${if (state.connected) "已连接" else "未连接"}", style = MaterialTheme.typography.labelMedium)
            Text(state.message, style = MaterialTheme.typography.bodySmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.connected || state.busy) TextButton(onClick = model::disconnect) { Text("取消 / 断开连接") }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (page) {
                "连接" -> Connections(model, state)
                "工作台" -> Workbench(model, state)
                else -> {
                    Text("开发占位包名：com.example.datasail\n首次发行前需确认正式身份与签名。")
                    Text("Android 8.0 为最低构建目标；各系统实测范围见交付测试报告。密码不持久化，进入后台会断开连接，不自动重试。")
                    Text("SQL Server：mssql-jdbc 13.6.0.jre8（MIT）\nMySQL / MariaDB：MariaDB Connector/J 3.5.10（LGPL-2.1-or-later）\nPostgreSQL：pgjdbc 42.7.14（BSD-2-Clause）")
                    Text("AI、收藏尚未实现；新增数据库当前只读。SQL 过滤不是授权边界，生产连接请使用数据库只读账号。")
                    DatabaseSupport()
                    val probeState by probe.state.collectAsStateWithLifecycle()
                    ProbeForm(probe, probeState)
                }
            }
        }
    } }
    state.preview?.let { preview -> AlertDialog(onDismissRequest = { if (!state.busy) model.discardPreview() },
        title = { Text("确认${preview.kind.label} 1 行") },
        text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(preview.target); Text("定位：${preview.keyDescription}")
            Text("确认 2 分钟内有效；提交前再检查元数据及原行。事务仅在提交时开启。")
            preview.changes.forEach { Text("${it.column}\n原值：${it.before ?: "（无）"}\n新值：${it.after ?: "（删除）"}") }
            Text("参数化 SQL：\n${preview.sql}")
        } }, confirmButton = { Button(enabled = !state.busy, onClick = { model.confirm(preview.token) }) { Text("确认提交") } },
        dismissButton = { TextButton(onClick = model::discardPreview) { Text("取消") } }) }
}

@Composable
private fun Connections(model: WorkspaceViewModel, state: WorkspaceUiState) {
    val saved by model.saved.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(SavedConnection()) }
    var password by remember { mutableStateOf("") }
    var authorized by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) password = "" }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { model.importCa(it) { hash -> draft = draft.copy(ca = hash); authorized = false } } }
    val sqlitePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { model.importSqlite(it) { id -> draft = draft.copy(database = id); authorized = false } } }
    Text("已保存连接（仅元数据）", style = MaterialTheme.typography.titleMedium)
    saved.forEach { connection -> Row {
        TextButton(onClick = { draft = connection; password = ""; authorized = false }) { Text("${connection.name} · ${connection.kind.label}") }
        TextButton(onClick = { draft = connection.copy(id = UUID.randomUUID().toString(), name = connection.name + " 副本"); password = ""; authorized = false }) { Text("复制") }
        TextButton(onClick = { model.delete(connection.id); if (draft.id == connection.id) draft = SavedConnection() }) { Text("删除") }
    } }
    TextButton(onClick = { draft = SavedConnection(); password = ""; authorized = false }) { Text("新建连接") }
    fun change(value: SavedConnection) { draft = value; authorized = false }
    Choice("数据库类型", DatabaseKind.entries.map { it.label }, draft.kind.label, !state.busy) { label ->
        val kind = DatabaseKind.entries.first { it.label == label }
        change(draft.copy(kind = kind, port = kind.defaultPort.toString(), database = if ((kind == DatabaseKind.SQLITE) != (draft.kind == DatabaseKind.SQLITE)) "" else draft.database)); password = ""
    }
    OutlinedTextField(draft.name, { change(draft.copy(name = it)) }, label = { Text("连接名称") }, singleLine = true)
    if (draft.kind == DatabaseKind.SQLITE) {
        Text("选择已导出的完整 SQLite 文件（≤64 MiB，非 WAL 模式）。应用保存只读副本。")
        TextButton(onClick = { sqlitePicker.launch("*/*") }) { Text("选择 SQLite 文件") }
        Text(if (draft.database.matches(Regex("[a-f0-9]{64}"))) "已选择副本" else "尚未选择文件")
    } else {
    OutlinedTextField(draft.host, { change(draft.copy(host = it)) }, label = { Text("服务器 DNS / IPv4") }, singleLine = true)
    OutlinedTextField(draft.port, { change(draft.copy(port = it)) }, label = { Text("端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    OutlinedTextField(draft.database, { change(draft.copy(database = it)) }, label = { Text("数据库") }, singleLine = true)
    OutlinedTextField(draft.username, { change(draft.copy(username = it)) }, label = { Text("用户名") }, singleLine = true)
    OutlinedTextField(password, { password = it }, label = { Text("密码（不保存）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    Row { Checkbox(draft.production, { change(draft.copy(production = it)) }); Text("生产环境（本版只读）") }
    Text("TLS：强制加密并验证证书和主机名。${if (draft.ca == null) "使用系统 CA。" else "自选 CA SHA-256：\n${draft.ca}"}")
    Text("仅导入你已核对来源和指纹的 CA；它可为此连接签发受信任证书。")
    Row { TextButton(onClick = { picker.launch("*/*") }) { Text("选择 CA 证书") }
        TextButton(onClick = { change(draft.copy(ca = null)) }) { Text("恢复系统 CA") } }
    }
    Row { Checkbox(authorized, { authorized = it }); Text("我已获准访问上述目标；权限由数据库账号控制。") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { model.save(draft) }) { Text("保存配置") }
        Button(enabled = authorized && (draft.kind == DatabaseKind.SQLITE || password.isNotEmpty()) && !state.busy, onClick = { val secret = password.toCharArray(); password = ""; model.connect(draft, secret) }) { Text("连接") }
    }
}

@Composable
private fun Workbench(model: WorkspaceViewModel, state: WorkspaceUiState) {
    if (!state.connected) { Text("请在连接页选择数据库并连接，然后浏览表或执行查询。"); return }
    var search by remember { mutableStateOf("") }
    var sql by remember { mutableStateOf("SELECT 1 AS 示例") }
    var selected by remember(state.result) { mutableStateOf<Int?>(null) }
    var editing by remember(state.result) { mutableStateOf<MutationKind?>(null) }
    Choice("数据库", state.catalogs, state.catalog, !state.busy, model::switchCatalog)
    Choice("Schema", state.schemas, state.schema, !state.busy, model::tables)
    Row { Switch(state.writable, { model.writable(it) }, enabled = !state.busy && !state.production && state.kind == DatabaseKind.SQL_SERVER); Text("允许本次测试会话写入（单行确认）") }
    OutlinedTextField(search, { search = it }, label = { Text("筛选表 / 视图") }, singleLine = true)
    // Object chooser remains bounded; data rows are limited by the core to 100.
    val matching = state.tables.filter { it.name.contains(search, ignoreCase = true) }
    Choice("表 / 视图（${matching.size}）", matching.map { it.name }, state.result?.tableSchema?.table?.name ?: "选择对象", !state.busy) { name -> model.page(matching.first { it.name == name }) }
    OutlinedTextField(sql, { sql = it }, label = { Text("单条 SELECT 查询") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    Text("查询结果只读，最多显示 100 行 / 64 列；不支持 CTE、变量、多语句和写 SQL。请使用最小权限账号。")
    Button(enabled = !state.busy, onClick = { model.query(sql) }) { Text("执行查询") }
    state.result?.let { result ->
        Text("${result.tableSchema?.table?.label ?: "查询结果（只读）"} · 第 ${result.offset + 1} 行起 · ${result.rows.size} 行${if (result.hasMore) " · 还有结果" else ""}")
        Text("单字段最多 2048 字符 / 二进制 256 字节；超限值标记未加载。")
        if (result.rows.isEmpty()) Text("执行成功，没有记录。")
        result.tableSchema?.let { schema ->
            Text(schema.readOnlyReason ?: "支持安全单行编辑；自增、计算列及主键修改禁用。")
            Text(schema.columns.joinToString("\n") { "${it.name} · ${it.typeName}(${it.size},${it.scale})${if (it.primaryKeyOrder > 0) " · PK" else ""}${if (it.nullable) " · NULL" else " · NOT NULL"}${if (it.identity) " · 自增" else ""}" })
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row { result.columns.forEach { column -> Text("${column.name}\n${column.typeName}",
                modifier = Modifier.width(180.dp), style = MaterialTheme.typography.labelLarge, maxLines = 2) } }
            LazyColumn(Modifier.width((180 * result.columns.size.coerceAtLeast(1)).dp).height(320.dp)) {
                itemsIndexed(result.rows) { rowIndex, row -> Row {
                    row.forEach { cell -> TextButton(onClick = { selected = rowIndex }, modifier = Modifier.width(180.dp).height(52.dp)) {
                        Text((if (selected == rowIndex) "✓ " else "") + Values.display(cell).ifEmpty { "（空字符串）" }, maxLines = 2)
                    } }
                } }
            }
        }
        result.tableSchema?.let { schema ->
            Row {
                TextButton(enabled = !state.busy && result.offset > 0, onClick = { model.page(schema.table, (result.offset - 100).coerceAtLeast(0)) }) { Text("上一页") }
                TextButton(enabled = !state.busy, onClick = { model.page(schema.table, result.offset) }) { Text("刷新") }
                TextButton(enabled = !state.busy && result.hasMore && result.stableOrder && result.offset + result.rows.size <= 1_000_000, onClick = { model.page(schema.table, result.offset + result.rows.size) }) { Text("下一页") }
            }
            if (result.stableOrder) Text("按主键排序；并发增删可能改变页边界，分页不是数据库快照。")
            val canEdit = state.writable && schema.readOnlyReason == null && !state.busy
            Row {
                Button(enabled = canEdit, onClick = { editing = MutationKind.INSERT }) { Text("新增") }
                TextButton(enabled = canEdit && selected != null, onClick = { editing = MutationKind.UPDATE }) { Text("修改选中行") }
                TextButton(enabled = canEdit && selected != null, onClick = { model.preview(MutationKind.DELETE, selected, emptyMap()) }) { Text("删除选中行") }
            }
            editing?.let { kind -> EditForm(schema, result, kind, selected, { editing = null }) { values ->
                editing = null; model.preview(kind, selected, values)
            } }
        }
        selected?.let { row -> Text("记录详情（第 ${result.offset + row + 1} 行）", style = MaterialTheme.typography.titleMedium)
            result.columns.forEachIndexed { index, column -> Text("${column.name} (${column.typeName})：${Values.display(result.rows[row][index]).ifEmpty { "（空字符串）" }}") }
        }
    }
}

@Composable
private fun Choice(label: String, values: List<String>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(enabled = enabled && values.isNotEmpty(), onClick = { expanded = true }) { Text("$label：$selected") }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            values.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { expanded = false; onSelect(value) }) }
        }
    }
}

@Composable
private fun EditForm(schema: TableSchema, page: ResultPage, kind: MutationKind, row: Int?, dismiss: () -> Unit, preview: (Map<String, CellInput>) -> Unit) {
    val columns = schema.columns.filter { it.editable && (kind == MutationKind.INSERT || it.primaryKeyOrder == 0) }
    var inputs by remember { mutableStateOf(columns.associate { col ->
        val old = if (kind == MutationKind.UPDATE) page.rows[row!!][schema.columns.indexOf(col)] else null
        col.name to CellInput(if (old == null || old == DbValue.Null) "" else if (old is DbValue.Text) old.value else Values.display(old), old == DbValue.Null)
    }) }
    var included by remember { mutableStateOf(columns.filter { kind == MutationKind.UPDATE || (!it.nullable && !it.hasDefault) }.map { it.name }.toSet()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("${kind.label}记录 · ${schema.table.label}") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
            Text("勾选要写入的列；未勾选列保持原值或使用数据库默认值。NULL 与空字符串分别处理。")
            columns.forEach { col ->
                val value = inputs.getValue(col.name)
                Row { Checkbox(col.name in included, { included = if (it) included + col.name else included - col.name }); Text("${col.name} · ${col.typeName}") }
                OutlinedTextField(value.text, { inputs = inputs + (col.name to value.copy(text = it)) }, enabled = col.name in included && !value.isNull, label = { Text(col.name) })
                if (col.nullable) Row { Checkbox(value.isNull, { inputs = inputs + (col.name to value.copy(isNull = it)) }, enabled = col.name in included); Text("NULL") }
            }
        }
    }, confirmButton = { Button(onClick = { preview(inputs.filterKeys { it in included }) }) { Text("预览变更") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun DatabaseSupport() {
    var search by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text("数据库支持范围") }
    if (expanded) {
        Text("统一管理关系型、文档、键值、搜索等数据库是产品目标。规划中的数据库尚不能连接；兼容协议不代表已经通过验收。")
        OutlinedTextField(search, { search = it }, label = { Text("搜索数据库名称 / 类型") }, singleLine = true)
        DatabaseCatalog.targets.filter { it.name.contains(search, true) || it.family.contains(search, true) }.forEach {
            Text("${it.name} · ${it.family}\n${it.capability}")
        }
    }
}
