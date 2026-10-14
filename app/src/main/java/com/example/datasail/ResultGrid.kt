package com.example.datasail

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.datasail.core.*

internal fun spreadsheetText(value: DbValue): String = if (value is DbValue.Text) value.value else Values.display(value)
internal fun spreadsheetColumn(index: Int): String {
    var n = index + 1; var label = ""
    while (n > 0) { n--; label = ('A' + n % 26) + label; n /= 26 }
    return label
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ResultGrid(page: ResultPage, state: WorkspaceUiState, settings: AppSettings, selectRow: (Int) -> Unit,
    preview: (Int, Map<String, CellInput>) -> Unit, expanded: Boolean = false, changeView: ((TableView) -> Unit)? = null) {
    var filterColumn by remember(page) { mutableStateOf<String?>(null) }
    var selected by remember(page) { mutableStateOf<Pair<Int, Int>?>(null) }
    var editing by remember(page) { mutableStateOf(false) }
    var input by remember(page) { mutableStateOf(CellInput()) }
    var fullscreen by remember { mutableStateOf(false) }
    var chooseColumns by remember { mutableStateOf(false) }
    var rowDetails by remember(page) { mutableStateOf(false) }
    var visible by remember(page.columns) { mutableStateOf(page.columns.indices.toSet()) }
    var width by remember(settings.columnWidth) { mutableIntStateOf(settings.columnWidth) }
    var wrap by remember(settings.wrapCells) { mutableStateOf(settings.wrapCells) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val viewport = (with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value } * (if (expanded) 0.58f else 0.38f)).coerceIn(140f, 520f).dp
    val horizontal = remember(page.tableSchema?.table, page.columnOffset, page.columns) { ScrollState(0) }
    val rowsScroll = remember(page.tableSchema?.table, page.offset) { androidx.compose.foundation.lazy.LazyListState() }
    val gridFocus = remember { FocusRequester() }
    val editFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val lineHeight = (settings.gridFont * 1.4f * zoom).sp
    val rowHeight = maxOf(((if (wrap) 88 else if (settings.compactGrid) 44 else 60) * zoom).dp, with(LocalDensity.current) { lineHeight.toDp() } * (if (wrap) 3 else 1) + (16 * zoom).dp)
    val headerHeight = maxOf((64 * zoom).dp, with(LocalDensity.current) { (20 * zoom).sp.toDp() } * (if (settings.showColumnTypes) 3 else 2) + (16 * zoom).dp)
    fun editable(row: Int, column: Int): Boolean = state.writable && state.kind == DatabaseKind.SQL_SERVER && !state.busy &&
        page.tableSchema?.readOnlyReason == null && page.tableSchema?.columns?.getOrNull(page.columnOffset + column)?.let { it.editable && it.primaryKeyOrder == 0 } == true &&
        page.rows[row].none { it is DbValue.Unavailable }
    fun choose(row: Int, column: Int, edit: Boolean = false) {
        if (editing) return
        selected = row to column; selectRow(row)
        val value = page.rows[row][column]
        input = CellInput(if (value == DbValue.Null) "" else spreadsheetText(value), value == DbValue.Null)
        editing = edit && editable(row, column)
        if (!editing) gridFocus.requestFocus()
    }
    LaunchedEffect(editing, selected, input.isNull) { if (editing && !input.isNull) editFocus.requestFocus() }
    if (changeView != null) {
        TableViewBar(page, state.busy, { filterColumn = it }, changeView)
        filterColumn?.let { ColumnViewDialog(page, it, state.kind, { filterColumn = null }, changeView) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { zoom = 1f }, label = { Text("${(zoom * 100).toInt()}% · 重置") }, modifier = Modifier.testTag("grid-zoom"))
        if (!expanded) AssistChip(onClick = { fullscreen = true }, label = { Text("全屏表格") })
        AssistChip(onClick = { chooseColumns = true }, label = { Text("显示列 ${visible.size}/${page.columns.size}") })
        FilterChip(selected = wrap, onClick = { wrap = !wrap }, label = { Text("换行") })
        AssistChip(onClick = { width = when (width) { 140 -> 200; 200 -> 280; 280 -> 360; else -> 140 } }, label = { Text("列宽 $width") })
    }
    Text("双指缩放 · 单指滚动 · 双击编辑", style = MaterialTheme.typography.bodySmall)
    selected?.let { (row, column) ->
        val definition = page.tableSchema?.columns?.getOrNull(page.columnOffset + column)
        Text("${spreadsheetColumn(page.columnOffset + column)}${page.offset + row + 1} · ${page.columns[column].name}", style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(spreadsheetText(page.rows[row][column]).ifEmpty { "（空字符串）" }, modifier = Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = (settings.gridFont * zoom).sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { rowDetails = true }) { Text("整行详情") }
            TextButton(enabled = page.rows[row][column] !is DbValue.Unavailable, onClick = {
                val clip = ClipData.newPlainText("DataSail 单元格", spreadsheetText(page.rows[row][column]))
                if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }) { Text("复制单元格") }
            TextButton(enabled = editable(row, column) && !editing, onClick = { val old = page.rows[row][column]; input = CellInput(if (old == DbValue.Null) "" else spreadsheetText(old), old == DbValue.Null); editing = true }) { Text("编辑单元格") }
        }
        if (editing) {
            if (definition?.nullable == true) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(input.isNull, { input = input.copy(isNull = it) }, modifier = Modifier.testTag("cell-null")); Text("设为 NULL")
            }
            if (input.isNull) Text("待提交值：NULL")
            Row {
                Button(enabled = editable(row, column), onClick = { editing = false; preview(row, mapOf(page.columns[column].name to input)) }) { Text("预览单元格修改") }
                TextButton(onClick = { editing = false }) { Text("取消修改") }
            }
            Text("先预览或取消当前修改，再选择其它单元格。", style = MaterialTheme.typography.bodySmall)
        } else if (!editable(row, column)) Text(page.tableSchema?.readOnlyReason ?: if (page.tableSchema == null) "查询结果只读；请从表浏览入口编辑" else if (state.writable) "此列只读（主键、自增或不支持的类型）" else "当前只读；请点击上方「开启编辑」", style = MaterialTheme.typography.bodySmall)
    }
    Surface(modifier = Modifier.testTag("result-grid").pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
                    val factor = event.calculateZoom()
                    if (factor.isFinite() && factor > 0f) zoom = (zoom * factor).coerceIn(0.65f, 2f)
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().focusRequester(gridFocus).onPreviewKeyEvent { event ->
            val location = selected
            if (editing || location == null || event.type != KeyEventType.KeyDown) false else {
                val (row, column) = location
                val next = when (event.key) {
                    Key.DirectionLeft -> row to (column - 1).coerceAtLeast(0)
                    Key.DirectionRight -> row to (column + 1).coerceAtMost(page.columns.lastIndex)
                    Key.DirectionUp -> (row - 1).coerceAtLeast(0) to column
                    Key.DirectionDown -> (row + 1).coerceAtMost(page.rows.lastIndex) to column
                    else -> null
                }
                if (next == null) false else { choose(next.first, next.second); true }
            }
        }.focusable().horizontalScroll(horizontal)) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.width(48.dp).height(headerHeight), contentAlignment = Alignment.Center) { Text("#", style = MaterialTheme.typography.labelMedium) }
                page.columns.forEachIndexed { index, column -> if (index in visible) Column(Modifier.width((width * zoom).dp).height(headerHeight).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).testTag("column-${column.name}").clickable(enabled = changeView != null && !state.busy) { filterColumn = column.name }.padding((8 * zoom).dp)) {
                    val sort = page.view.sorts.firstOrNull { it.column == column.name }
                    val mark = (if (sort == null) "" else if (sort.descending) " ↓" else " ↑") + (if (page.view.filters.any { it.column == column.name }) " •" else "")
                    Text("${column.name}$mark${if (changeView != null) " ▾" else ""}", fontSize = (14 * zoom).sp, lineHeight = (20 * zoom).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (settings.showColumnTypes) Text(column.typeName, fontSize = (11 * zoom).sp, lineHeight = (15 * zoom).sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            LazyColumn(Modifier.width((48 + width * zoom * visible.size).dp).height(viewport), state = rowsScroll) {
                itemsIndexed(page.rows) { rowIndex, row -> Row {
                    Box(Modifier.width(48.dp).height(rowHeight).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Text((page.offset + rowIndex + 1).toString(), style = MaterialTheme.typography.labelSmall)
                    }
                    row.forEachIndexed { columnIndex, value -> if (columnIndex in visible) {
                        val active = selected == (rowIndex to columnIndex)
                        val background = when { active -> MaterialTheme.colorScheme.primaryContainer; rowIndex % 2 == 0 -> MaterialTheme.colorScheme.surface; else -> MaterialTheme.colorScheme.surfaceContainerLow }
                        Box(Modifier.width((width * zoom).dp).height(rowHeight).background(background)
                            .border(if (active) 2.dp else 0.5.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                            .testTag("cell-$rowIndex-$columnIndex")
                            .combinedClickable(onClick = { choose(rowIndex, columnIndex) }, onDoubleClick = { choose(rowIndex, columnIndex, true) })
                            .padding((8 * zoom).dp), contentAlignment = Alignment.CenterStart) {
                            if (active && editing && !input.isNull) BasicTextField(input.text, { if (it.length <= 4000) input = input.copy(text = it) },
                                modifier = Modifier.fillMaxWidth().focusRequester(editFocus).testTag("cell-editor"), singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = (settings.gridFont * zoom).sp, lineHeight = lineHeight, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace))
                            else Text((if (active && editing && input.isNull) "NULL" else spreadsheetText(value)).ifEmpty { "（空字符串）" }, fontSize = (settings.gridFont * zoom).sp, lineHeight = lineHeight, maxLines = if (wrap) 3 else 1, overflow = TextOverflow.Ellipsis,
                                color = if (value == DbValue.Null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        }
                    } }
                } }
            }
        }
    }
    if (chooseColumns) AlertDialog(onDismissRequest = { chooseColumns = false }, title = { Text("显示字段") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) { itemsIndexed(page.columns) { index, column ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(index in visible, { checked -> if (checked) visible = visible + index else if (visible.size > 1) visible = visible - index })
                Text("${column.name} · ${column.typeName}")
            }
        } }
    }, confirmButton = { TextButton(onClick = { chooseColumns = false }) { Text("完成") } }, dismissButton = { TextButton(onClick = { visible = page.columns.indices.toSet() }) { Text("全部显示") } })
    if (rowDetails && selected != null) AlertDialog(onDismissRequest = { rowDetails = false }, title = { Text("第 ${page.offset + selected!!.first + 1} 行 · 已加载字段") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp)) { itemsIndexed(page.columns) { index, column -> Column(Modifier.padding(vertical = 8.dp)) {
            Text("${column.name} · ${column.typeName}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SelectionContainer { Text(spreadsheetText(page.rows[selected!!.first][index]).ifEmpty { "（空字符串）" }, fontFamily = FontFamily.Monospace) }
            HorizontalDivider()
        } } }
    }, confirmButton = { TextButton(onClick = { rowDetails = false }) { Text("关闭") } })
    if (fullscreen) androidx.compose.ui.window.Dialog(onDismissRequest = { fullscreen = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(page.tableSchema?.table?.label ?: "查询结果", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { fullscreen = false }) { Text("退出全屏") }
                }
                Text("${state.connectionName} / ${state.catalog} / ${state.schema} · ${if (state.production) "生产" else "常规"} · ${if (state.writable) "可写" else "只读"}", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResultGrid(page, state, settings, selectRow, { row, inputs -> fullscreen = false; preview(row, inputs) }, expanded = true, changeView = changeView)
                }
            }
        }
    }

}
