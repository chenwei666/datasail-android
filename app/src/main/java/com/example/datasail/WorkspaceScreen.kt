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
    val contentScroll = rememberScrollState()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(page) { focus.clearFocus(); keyboard?.hide(); contentScroll.scrollTo(0) }
    LaunchedEffect(state.connected) { if (state.connected) page = "工作台" }
    var exportPreview by remember { mutableStateOf<Pair<String, ResultPage>?>(null) }
    var exportSnapshot by remember { mutableStateOf<ResultPage?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val snapshot = exportSnapshot; exportSnapshot = null
        if (uri != null && snapshot != null) model.exportPage(uri, snapshot)
    }
    Scaffold(bottomBar = { NavigationBar {
        listOf("连接", "工作台", "AI 助手", "收藏", "设置").forEach { title ->
            val available = title != "AI 助手"
            NavigationBarItem(selected = page == title, onClick = { page = title }, enabled = available,
                icon = { NavigationGlyph(title) }, label = { Text(title) })
        }
    } }) { padding -> Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("数舟", style = MaterialTheme.typography.titleLarge)
                Text("DataSail · V${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (state.connected || state.busy) "${state.target} / ${state.catalog} / ${state.schema} · ${if (state.production) "生产" else "测试"} · ${if (state.writable) "可写" else "只读"} · ${if (state.connected) "已连接" else "连接中"}" else "未连接 · 默认只读",
                style = MaterialTheme.typography.labelMedium)
            if (state.message != "请选择已授权的数据库连接；默认只读。") Text(state.message, style = MaterialTheme.typography.bodySmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.connected || state.busy) TextButton(onClick = model::disconnect) { Text("取消 / 断开连接") }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).widthIn(max = 960.dp).fillMaxWidth().align(androidx.compose.ui.Alignment.CenterHorizontally).verticalScroll(contentScroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (page) {
                "连接" -> Connections(model, state)
                "工作台" -> Workbench(model, state) { exportPreview = state.target + " / " + state.catalog to it }
                "收藏" -> QueryLibrary(model, state) { page = "工作台" }
                else -> {
                    Text("开发占位包名：com.example.datasail\n首次发行前需确认正式身份与签名。")
                    Text("Android 8.0 为最低构建目标；各系统实测范围见交付测试报告。密码不持久化，进入后台会断开连接，不自动重试。")
                    Text("SQL Server：mssql-jdbc 13.6.0.jre8（MIT）\nMySQL / MariaDB：MariaDB Connector/J 3.5.10（LGPL-2.1-or-later）\nPostgreSQL：pgjdbc 42.7.14（BSD-2-Clause）\nMongoDB：Java Driver 5.6.5（Apache-2.0）")
                    Text("AI 尚未实现；新增数据库当前只读。SQL 过滤不是授权边界，生产连接请使用数据库只读账号。")
                    DatabaseSupport()
                    val probeState by probe.state.collectAsStateWithLifecycle()
                    ProbeForm(probe, probeState)
                }
            }
        }
    } }
    exportPreview?.let { (target, result) -> AlertDialog(onDismissRequest = { exportPreview = null },
        title = { Text("导出当前结果页？") }, text = { Text("$target\n仅导出当前已加载的 ${result.rows.size} 行。UTF-8 CSV，不是整表备份。NULL 写作 \\N，文字中的反斜杠前缀转义；可能被电子表格当成公式的文字前加单引号。选择保存位置时连接会断开。") },
        confirmButton = { TextButton(onClick = { exportSnapshot = result; exportPreview = null; exporter.launch("DataSail-page.csv") }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { exportPreview = null }) { Text("取消") } }) }
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
private fun Workbench(model: WorkspaceViewModel, state: WorkspaceUiState, export: (ResultPage) -> Unit) {
    if (!state.connected) { Text("请在连接页选择数据库并连接，然后浏览表或执行查询。"); return }
    if (state.kind.isSearch) { SearchWorkbench(model, state); return }
    if (state.kind.isDocument) { DocumentWorkbench(model, state); return }
    if (state.kind.isKeyValue) { KeyValueWorkbench(model, state); return }
    var search by remember { mutableStateOf("") }
    val sql by model.draftSql.collectAsStateWithLifecycle()
    var savingQuery by remember { mutableStateOf(false) }
    var queryName by remember { mutableStateOf("") }
    var selected by remember(state.result) { mutableStateOf<Int?>(null) }
    var editing by remember(state.result) { mutableStateOf<MutationKind?>(null) }
    Choice("数据库", state.catalogs, state.catalog, !state.busy, model::switchCatalog)
    Choice("Schema", state.schemas, state.schema, !state.busy, model::tables)
    Row { Switch(state.writable, { model.writable(it) }, enabled = !state.busy && !state.production && state.kind == DatabaseKind.SQL_SERVER); Text("允许本次测试会话写入（单行确认）") }
    OutlinedTextField(search, { search = it }, label = { Text("筛选表 / 视图") }, singleLine = true)
    // Object chooser remains bounded; data rows are limited by the core to 100.
    val matching = state.tables.filter { it.name.contains(search, ignoreCase = true) }
    Choice("表 / 视图（${matching.size}）", matching.map { it.name }, state.result?.tableSchema?.table?.name ?: "选择对象", !state.busy) { name -> model.page(matching.first { it.name == name }) }
    OutlinedTextField(sql, model::editSql, label = { Text("单条 SELECT 查询") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    Text("查询结果只读，最多显示 100 行 / 64 列；不支持 CTE、变量、多语句和写 SQL。请使用最小权限账号。")
    Button(enabled = !state.busy, onClick = { model.query(sql) }) { Text("执行查询") }
    TextButton(enabled = !state.busy, onClick = { savingQuery = true; queryName = "" }) { Text("收藏查询") }
    if (savingQuery) AlertDialog(onDismissRequest = { savingQuery = false }, title = { Text("收藏 SELECT 草稿") }, text = {
        Column { Text("仅保存在本机。SQL 可能含业务内容，请确认没有密码或密钥。载入后需要手动执行。"); OutlinedTextField(queryName, { queryName = it }, label = { Text("查询名称") }, singleLine = true) }
    }, confirmButton = { TextButton(enabled = queryName.isNotBlank() && queryName.length <= 80, onClick = { model.saveQuery(queryName); savingQuery = false }) { Text("保存收藏") } }, dismissButton = { TextButton(onClick = { savingQuery = false }) { Text("取消") } })
    state.result?.let { result ->
        OutlinedButton(enabled = !state.busy && PageCsv.canExport(result), onClick = { export(result) }) { Text("导出当前页 CSV") }
        if (!PageCsv.canExport(result)) Text("本页含未完整加载字段，暂不能导出。")
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
