package com.example.datasail

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.*
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ResultGrid(page: ResultPage, state: WorkspaceUiState, settings: AppSettings, selectRow: (Int) -> Unit,
    preview: (Int, Map<String, CellInput>) -> Unit) {
    var selected by remember(page) { mutableStateOf<Pair<Int, Int>?>(null) }
    var editing by remember(page) { mutableStateOf(false) }
    var input by remember(page) { mutableStateOf(CellInput()) }
    val horizontal = rememberScrollState()
    val gridFocus = remember { FocusRequester() }
    val editFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val rowHeight = if (settings.compactGrid) 44.dp else 60.dp
    fun editable(row: Int, column: Int): Boolean = state.writable && !state.production && state.kind == DatabaseKind.SQL_SERVER && !state.busy &&
        page.tableSchema?.readOnlyReason == null && page.tableSchema?.columns?.getOrNull(column)?.let { it.editable && it.primaryKeyOrder == 0 } == true &&
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
    Text("点击选择单元格 · 双击可编辑字段 · 修改须预览后提交", style = MaterialTheme.typography.bodySmall)
    Surface(modifier = Modifier.testTag("result-grid"), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
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
                Box(Modifier.width(48.dp).height(64.dp), contentAlignment = Alignment.Center) { Text("#", style = MaterialTheme.typography.labelMedium) }
                page.columns.forEachIndexed { index, column -> Column(Modifier.width(176.dp).height(64.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(8.dp)) {
                    Text("${spreadsheetColumn(index)}  ${column.name}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (settings.showColumnTypes) Text(column.typeName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            LazyColumn(Modifier.width((48 + 176 * page.columns.size).dp).heightIn(min = 100.dp, max = 360.dp)) {
                itemsIndexed(page.rows) { rowIndex, row -> Row {
                    Box(Modifier.width(48.dp).height(rowHeight).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Text((page.offset + rowIndex + 1).toString(), style = MaterialTheme.typography.labelSmall)
                    }
                    row.forEachIndexed { columnIndex, value ->
                        val active = selected == (rowIndex to columnIndex)
                        val background = when { active -> MaterialTheme.colorScheme.primaryContainer; rowIndex % 2 == 0 -> MaterialTheme.colorScheme.surface; else -> MaterialTheme.colorScheme.surfaceContainerLow }
                        Box(Modifier.width(176.dp).height(rowHeight).background(background)
                            .border(if (active) 2.dp else 0.5.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                            .testTag("cell-$rowIndex-$columnIndex")
                            .combinedClickable(onClick = { choose(rowIndex, columnIndex) }, onDoubleClick = { choose(rowIndex, columnIndex, true) })
                            .padding(8.dp), contentAlignment = Alignment.CenterStart) {
                            if (active && editing && !input.isNull) BasicTextField(input.text, { if (it.length <= 4000) input = input.copy(text = it) },
                                modifier = Modifier.fillMaxWidth().focusRequester(editFocus).testTag("cell-editor"), singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = settings.gridFont.sp, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace))
                            else Text((if (active && editing && input.isNull) "NULL" else spreadsheetText(value)).ifEmpty { "（空字符串）" }, fontSize = settings.gridFont.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (value == DbValue.Null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                } }
            }
        }
    }
    selected?.let { (row, column) ->
        val definition = page.tableSchema?.columns?.getOrNull(column)
        Text("${spreadsheetColumn(column)}${page.offset + row + 1} · ${page.columns[column].name}", style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(spreadsheetText(page.rows[row][column]).ifEmpty { "（空字符串）" }, fontFamily = FontFamily.Monospace, fontSize = settings.gridFont.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        } else if (!editable(row, column)) Text(page.tableSchema?.readOnlyReason ?: if (page.tableSchema == null) "查询结果只读；请从表浏览入口编辑" else if (state.writable) "此列只读（主键、自增或不支持的类型）" else "只读；SQL Server 测试会话可显式开启写入", style = MaterialTheme.typography.bodySmall)
    }
}
