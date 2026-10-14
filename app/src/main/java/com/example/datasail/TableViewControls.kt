package com.example.datasail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.datasail.core.*

@Composable
internal fun TableViewBar(page: ResultPage, busy: Boolean, choose: (String, ColumnFilter?) -> Unit, apply: (TableView) -> Unit) {
    val schema = page.tableSchema ?: return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { schema.columns.firstOrNull()?.let { choose(it.name, null) } }) { Text("筛选 / 排序") }
        page.view.filters.forEach { filter -> AssistChip(enabled = !busy, onClick = { choose(filter.column, filter) },
            label = { Text("${filter.column} ${filter.operator.label} ${filter.value.take(30)}") }) }
        page.view.sorts.forEach { sort -> AssistChip(enabled = !busy, onClick = { choose(sort.column, null) }, label = { Text("${sort.column} ${if (sort.descending) "↓" else "↑"}") }) }
        if (page.view != TableView()) TextButton(enabled = !busy, onClick = { apply(TableView()) }) { Text("清除条件") }
    }
}

@Composable
internal fun ColumnViewDialog(page: ResultPage, initial: String, kind: DatabaseKind, existing: ColumnFilter? = null, dismiss: () -> Unit, apply: (TableView) -> Unit) {
    val schema = page.tableSchema ?: return
    var column by remember(initial) { mutableStateOf(initial) }
    var operator by remember { mutableStateOf(existing?.operator ?: FilterOperator.EQ) }
    var value by remember { mutableStateOf(existing?.value ?: "") }
    var columns by remember { mutableStateOf(false) }
    var operators by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val definition = schema.columns.first { it.name == column }
    val availableOperators = TableViews.operators(definition, kind == DatabaseKind.SQL_SERVER, kind == DatabaseKind.SQLITE)
    val canSort = TableViews.sortable(definition, kind == DatabaseKind.SQL_SERVER, kind == DatabaseKind.SQLITE)
    LaunchedEffect(column) { if (operator !in availableOperators) operator = availableOperators.first() }
    fun submit(view: TableView) {
        try {
            TableViews.compile(schema, view, { "\"" + it.replace("\"", "\"\"") + "\"" }, sqlite = kind == DatabaseKind.SQLITE, sqlServer = kind == DatabaseKind.SQL_SERVER)
            apply(view); dismiss()
        } catch (e: WorkspaceRejected) { error = e.explanation }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("字段筛选与排序") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("条件在数据库端执行；多个条件同时满足。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { columns = true }, modifier = Modifier.fillMaxWidth()) { Text("字段：$column ▾") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = canSort, onClick = { submit(page.view.copy(sorts = listOf(ColumnSort(column)))) }) { Text("升序 ↑") }
                OutlinedButton(enabled = canSort, onClick = { submit(page.view.copy(sorts = listOf(ColumnSort(column, true)))) }) { Text("降序 ↓") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = canSort && (page.view.sorts.size < 3 || page.view.sorts.any { it.column == column }), onClick = { submit(page.view.copy(sorts = page.view.sorts.filterNot { it.column == column } + ColumnSort(column))) }) { Text("追加升序") }
                TextButton(enabled = canSort && (page.view.sorts.size < 3 || page.view.sorts.any { it.column == column }), onClick = { submit(page.view.copy(sorts = page.view.sorts.filterNot { it.column == column } + ColumnSort(column, true))) }) { Text("追加降序") }
            }
            if (!canSort) Text("此字段类型不支持排序", style = MaterialTheme.typography.bodySmall)
            if (page.view.sorts.isNotEmpty()) TextButton(onClick = { submit(page.view.copy(sorts = emptyList())) }) { Text("恢复主键排序") }
            HorizontalDivider()
            Box {
                OutlinedButton(onClick = { operators = true }) { Text("${operator.label} ▾") }
                DropdownMenu(operators, { operators = false }) { availableOperators.forEach { item -> DropdownMenuItem(text = { Text(item.label) }, onClick = { operator = item; operators = false }) } }
            }
            if (operator.needsValue) OutlinedTextField(value, { value = it.take(4000); error = "" }, label = { Text("筛选值") },
                supportingText = { Text("日期时间：yyyy-MM-dd HH:mm:ss；文本按原值匹配") }, modifier = Modifier.fillMaxWidth().testTag("table-filter-value"))
            page.view.filters.filter { it.column == column }.forEach { existing ->
                TextButton(onClick = { submit(page.view.copy(filters = page.view.filters - existing)) }) { Text("移除：${existing.operator.label} ${existing.value.take(30)} ×") }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { submit(TableViews.replaceFilter(page.view, existing, ColumnFilter(column, operator, if (operator.needsValue) value else ""))) }) { Text("应用筛选") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
    if (columns) AlertDialog(onDismissRequest = { columns = false }, title = { Text("选择字段") }, text = {
        var search by remember { mutableStateOf("") }
        Column { OutlinedTextField(search, { search = it }, label = { Text("搜索字段") }); LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(schema.columns.filter { it.name.contains(search, true) }) { item -> TextButton(onClick = { column = item.name; columns = false }) { Text("${item.name} · ${item.typeName}") } }
        } }
    }, confirmButton = { TextButton(onClick = { columns = false }) { Text("关闭") } })
}
