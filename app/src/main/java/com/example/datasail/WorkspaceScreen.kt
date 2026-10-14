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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
internal fun WorkspaceApp(model: WorkspaceViewModel, probe: ProbeViewModel, ai: AiViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("连接") }
    val contentScroll = rememberScrollState()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(page) { focus.clearFocus(); keyboard?.hide(); contentScroll.scrollTo(0) }
    var wasConnected by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.connected) { if (state.connected && !wasConnected) page = "工作台"; wasConnected = state.connected }
    var exportPreview by remember { mutableStateOf<Pair<String, ResultPage>?>(null) }
    var exportSnapshot by remember { mutableStateOf<ResultPage?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val snapshot = exportSnapshot; exportSnapshot = null
        if (uri != null && snapshot != null) model.exportPage(uri, snapshot)
    }
    Scaffold(bottomBar = { NavigationBar {
        listOf("连接", "工作台", "AI 助手", "收藏", "设置").forEach { title ->
            val available = true
            NavigationBarItem(selected = page == title || (page == "AI 设置" && title == "设置"), onClick = { page = title }, enabled = available,
                icon = { NavigationGlyph(title) }, label = { Text(title) })
        }
    } }) { padding -> Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("数舟", style = MaterialTheme.typography.titleLarge)
                Text("DataSail · V${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.connected || state.busy) {
                var sessionMenu by remember { mutableStateOf(false) }
                Box { TextButton(onClick = { sessionMenu = true }) { Text("连接操作 ▾") }
                    DropdownMenu(sessionMenu, { sessionMenu = false }) {
                        DropdownMenuItem(text = { Text("取消 / 断开连接") }, onClick = { sessionMenu = false; model.disconnect() })
                    }
                }
            }
            }
            Text(if (state.connected || state.busy) "${state.target} / ${state.catalog} / ${state.schema} · ${if (state.production) "生产" else "常规"} · ${if (state.writable) "可写" else "只读"} · ${if (state.connected) "已连接" else "连接中"}" else "未连接 · 默认只读",
                style = MaterialTheme.typography.labelMedium)
            if (state.message != "请选择已授权的数据库连接；默认只读。") Text(state.message, style = MaterialTheme.typography.bodySmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        }
        HorizontalDivider()
        Column(Modifier.weight(1f).fillMaxWidth().then(if (page == "工作台") Modifier else Modifier.verticalScroll(contentScroll)).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (page) {
                "连接" -> Connections(model, state)
                "工作台" -> Workbench(model, state, ai, { page = "连接" }) { exportPreview = state.target + " / " + state.catalog to it }
                "AI 助手" -> AiScreen(ai, model, { page = "AI 设置" }) { page = "工作台" }
                "AI 设置" -> AiSettingsScreen(ai, { page = "设置" }, { page = "AI 助手" })
                "收藏" -> QueryLibrary(model, state) { page = "工作台" }
                else -> SettingsScreen(model, ai, { page = "AI 设置" }) {
                    Text("应用标识：com.example.datasail · 构建：${BuildConfig.BUILD_TYPE}")
                    Text("当前十种引擎；除 SQL Server 外只读。AI 支持配置外部文本模型服务。")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Workbench(model: WorkspaceViewModel, state: WorkspaceUiState, ai: AiViewModel, openConnections: () -> Unit, export: (ResultPage) -> Unit) {
    if (!state.connected) {
        Text(if (state.connectionKey.isEmpty()) "请在连接页选择数据库并连接，然后浏览表或执行查询。" else "连接已断开。本次运行的草稿与表视图保留；重新连接后读取最新数据。")
        if (state.connectionKey.isNotEmpty()) Button(enabled = !state.busy, onClick = { model.reconnect(openConnections) }) { Text("重新连接并恢复工作台") }
        TextButton(onClick = openConnections) { Text("选择其他连接") }; return
    }
    if (state.kind.isSearch || state.kind.isDocument || state.kind.isKeyValue) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when { state.kind.isSearch -> SearchWorkbench(model, state); state.kind.isDocument -> DocumentWorkbench(model, state); else -> KeyValueWorkbench(model, state) }
        }; return
    }
    var tab by rememberSaveable(state.connectionKey) { mutableIntStateOf(if (state.message.startsWith("已载入草稿")) 1 else 0) }
    LaunchedEffect(state.message) { if (state.message.startsWith("已载入草稿")) tab = 1 }
    val paneScroll = remember(tab, state.result?.tableSchema?.table, state.result?.offset) { androidx.compose.foundation.ScrollState(0) }
    val paneFocus = androidx.compose.ui.platform.LocalFocusManager.current
    val paneKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(tab) { paneFocus.clearFocus(); paneKeyboard?.hide() }
    var picker by remember { mutableStateOf("") }
    var showObjects by remember(state.connectionKey) { mutableStateOf(state.result == null && !state.message.startsWith("已载入草稿")) }
    LaunchedEffect(state.result) { if (state.result != null) showObjects = false }
    var search by remember(state.catalog, state.schema) { mutableStateOf("") }
    var allowWrite by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var savingQuery by remember { mutableStateOf(false) }
    var queryName by remember { mutableStateOf("") }
    var selected by remember(state.result) { mutableStateOf<Int?>(null) }
    var editing by remember(state.result) { mutableStateOf<MutationKind?>(null) }
    val favorites by model.favoriteTables.collectAsStateWithLifecycle()
    val sql by model.draftSql.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val result = state.result
    val schema = result?.tableSchema
    var gridOptions by remember(state.connectionKey, schema?.table) { mutableStateOf(schema?.let { model.memory.grid(state.connectionKey, it.table) } ?: GridOptions()) }
    var history by remember { mutableStateOf(false) }
    val actions: @Composable () -> Unit = {
        if (schema != null && tab == 0) TableActionBar(model, state, result, selected, { if (state.writable) model.writable(false) else allowWrite = true }) { editing = it }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker = "数据库" }, enabled = !state.busy, modifier = Modifier.testTag("catalog-picker")) { Text("${state.catalog} ▾") }
        OutlinedButton(onClick = { picker = "Schema" }, enabled = !state.busy, modifier = Modifier.testTag("schema-picker")) { Text("${state.schema} ▾") }
        OutlinedButton(onClick = { showObjects = true }, enabled = !state.busy) { Text("浏览表 / 视图（${state.tables.size}）") }
    }
    if (picker.isNotEmpty()) SearchPicker(picker, if (picker == "数据库") state.catalogs else state.schemas,
        if (picker == "数据库") state.catalog else state.schema, { picker = "" }) {
        if (picker == "数据库") model.switchCatalog(it) else model.tables(it)
    }
    if (showObjects) AlertDialog(onDismissRequest = { showObjects = false }, title = { Text("表与视图 · ${state.tables.size}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(search, { search = it }, label = { Text("筛选表 / 视图") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            state.navigationIssues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            val matching = state.tables.filter { it.name.contains(search, true) }
            if (matching.isEmpty()) Text(if (state.busy) "正在加载数据库对象…" else "没有匹配对象，可切换数据库或 Schema、刷新对象。")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("object-list")) {
                itemsIndexed(matching, key = { _, t -> t.name }) { _, table ->
                    TextButton(enabled = !state.busy, onClick = { model.page(table); showObjects = false; tab = 0 }, modifier = Modifier.fillMaxWidth().testTag("object-${table.name}")) {
                        Column(Modifier.weight(1f)) { Text(table.name, Modifier.fillMaxWidth()); Text(if (table.view) "视图" else "表", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall) }
                        Text("查看 ›")
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showObjects = false }) { Text("关闭") } }, dismissButton = { TextButton(enabled = !state.busy, onClick = model::refreshNavigation) { Text("刷新数据库和对象") } })
    TabRow(selectedTabIndex = tab) { listOf("数据", "SQL 编辑器", "表结构").forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }) } }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(paneScroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tab == 1) {
            SqlEditor(sql, model::editSql, state, settings, ai, model)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.busy, onClick = { model.query(sql) }) { Text("执行查询") }
                TextButton(enabled = !state.busy, onClick = { savingQuery = true; queryName = "" }) { Text("收藏查询") }
                TextButton(onClick = { history = true }) { Text("查询历史") }
            }
            Text("单条只读查询；写入请使用数据页的编辑模式。", style = MaterialTheme.typography.bodySmall)
        }
        if (tab == 2) {
            val structure = state.selectedSchema
            if (structure == null) Text("请先选择一张表。") else { Text(structure.table.label, style = MaterialTheme.typography.titleMedium); TableStructurePanel(structure) }
        } else if (result != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(schema?.table?.label ?: "查询结果", style = MaterialTheme.typography.titleMedium)
                    Text("${if (result.rows.isEmpty()) 0 else result.offset + 1}–${result.offset + result.rows.size} 行 · ${result.totalColumns} 列", style = MaterialTheme.typography.bodySmall)
                }
                if (schema != null) TextButton(enabled = !state.busy && state.kind == DatabaseKind.SQL_SERVER && (state.writable || tableEditingBlockReason(result, state) == null), modifier = Modifier.testTag("allow-session-write"),
                    onClick = { if (state.writable) model.writable(false) else allowWrite = true }) { Text(if (state.writable) "退出编辑" else "开启编辑") }
                Box { TextButton(onClick = { more = true }) { Text("更多 ▾") }
                    DropdownMenu(more, { more = false }) {
                        if (schema != null) {
                            DropdownMenuItem(text = { Text(if (favorites.any { it.matches(state.connectionKey, schema.table) }) "取消表收藏" else "收藏此表") }, onClick = { more = false; model.toggleTableFavorite(schema.table) })
                            DropdownMenuItem(text = { Text("查看表结构") }, onClick = { more = false; tab = 2 })
                        }
                        DropdownMenuItem(text = { Text("导出当前页 CSV") }, enabled = !state.busy && PageCsv.canExport(result), onClick = { more = false; export(result) })
                    }
                }
            }
            if (schema != null && tab == 0) TableEditingStatus(result, state)
            if (result.totalColumns > result.columns.size) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("字段 ${result.columnOffset + 1}–${result.columnOffset + result.columns.size} / ${result.totalColumns}", Modifier.weight(1f))
                if (schema != null) {
                    TextButton(enabled = !state.busy && result.columnOffset > 0, onClick = { model.page(schema.table, result.offset, result.columnOffset - 64) }) { Text("上一组字段") }
                    TextButton(enabled = !state.busy && result.columnOffset + 64 < result.totalColumns, onClick = { model.page(schema.table, result.offset, result.columnOffset + 64) }) { Text("下一组字段") }
                }
            }
            if (result.rows.isEmpty()) Text("没有记录。")
            ResultGrid(result, state, settings, { selected = it }, { row, inputs -> model.preview(MutationKind.UPDATE, row, inputs) }, changeView = result.tableSchema?.let { schema -> { view -> model.page(schema.table, view = view) } },
                gridOptions = gridOptions, onGridOptions = { options -> gridOptions = options; schema?.let { model.memory.grid(state.connectionKey, it.table, options) } }, actions = actions)
        } else if (tab == 0) {
            Text("选择表后浏览或编辑数据，也可以切换到 SQL 编辑器编写查询。")
            Button(onClick = { showObjects = true }) { Text("选择表") }
        }
    }
    actions()
    if (schema != null) editing?.let { kind -> EditForm(schema, result, kind, selected, { editing = null }) { values -> editing = null; model.preview(kind, selected, values) } }
    if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("本次连接的查询历史") }, text = {
        val runs = model.memory.history(state.connectionKey)
        if (runs.isEmpty()) Text("尚无成功查询；历史仅保留在本次运行内，不保存结果行。")
        else LazyColumn(Modifier.heightIn(max = 380.dp)) { itemsIndexed(runs) { _, run ->
            TextButton(onClick = { model.editSql(run.sql); history = false }) { Column {
                Text(run.sql.take(300), maxLines = 4)
                Text("${run.catalog} / ${run.schema} · ${run.rows} 行 · ${run.elapsedMs} ms", style = MaterialTheme.typography.bodySmall)
            } }
        } }
    }, confirmButton = { TextButton(onClick = { history = false }) { Text("关闭") } }, dismissButton = { TextButton(onClick = { model.memory.clearHistory(); history = false }) { Text("清空历史") } })
    if (allowWrite) AlertDialog(onDismissRequest = { allowWrite = false }, title = { Text(if (state.production) "开启生产库编辑？" else "开启本次连接编辑？") },
        text = { Text("${state.connectionName} / ${state.catalog} / ${state.schema}\n每次修改需预览并确认，提交即写入服务器。切库、断开后恢复只读。无主键、触发器或不支持的类型仍不能编辑。") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { allowWrite = false; model.writable(true) }) { Text("确认开启编辑") } }, dismissButton = { TextButton(onClick = { allowWrite = false }) { Text("取消") } })
    if (savingQuery) AlertDialog(onDismissRequest = { savingQuery = false }, title = { Text("收藏 SELECT 草稿") }, text = {
        OutlinedTextField(queryName, { queryName = it }, label = { Text("查询名称") }, singleLine = true)
    }, confirmButton = { TextButton(enabled = queryName.isNotBlank() && queryName.length <= 80, onClick = { model.saveQuery(queryName); savingQuery = false }) { Text("保存收藏") } }, dismissButton = { TextButton(onClick = { savingQuery = false }) { Text("取消") } })
}

@Composable
private fun TableActionBar(model: WorkspaceViewModel, state: WorkspaceUiState, result: ResultPage, selected: Int?, toggleEditing: () -> Unit, edit: (MutationKind) -> Unit) {
    val schema = result.tableSchema ?: return
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !state.busy && result.offset > 0, onClick = { model.page(schema.table, (result.offset - 100).coerceAtLeast(0), result.columnOffset) }) { Text("上一页") }
        TextButton(enabled = !state.busy, onClick = { model.page(schema.table, result.offset, result.columnOffset) }) { Text("刷新") }
        TextButton(enabled = !state.busy && result.hasMore && result.stableOrder && result.offset + result.rows.size <= 1_000_000, onClick = { model.page(schema.table, result.offset + result.rows.size, result.columnOffset) }) { Text("下一页") }
        if (state.kind == DatabaseKind.SQL_SERVER) TextButton(enabled = !state.busy && (state.writable || tableEditingBlockReason(result, state) == null), onClick = toggleEditing) { Text(if (state.writable) "结束编辑" else "编辑模式") }
        if (state.writable) {
            val canEdit = schema.readOnlyReason == null && !state.busy && !state.resultStale
            TextButton(enabled = canEdit, onClick = { edit(MutationKind.INSERT) }) { Text("新增") }
            TextButton(enabled = canEdit && selected != null, onClick = { edit(MutationKind.UPDATE) }) { Text("修改选中行") }
            TextButton(enabled = canEdit && selected != null, onClick = { model.preview(MutationKind.DELETE, selected, emptyMap()) }) { Text("删除选中行") }
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
