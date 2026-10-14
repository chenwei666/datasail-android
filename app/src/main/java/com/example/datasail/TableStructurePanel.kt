package com.example.datasail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.datasail.core.TableSchema

/** Metadata already loaded for this exact table; never queries or modifies the database. */
@Composable
internal fun TableStructurePanel(schema: TableSchema) {
    var search by remember(schema.table) { mutableStateOf("") }
    var keysOnly by remember(schema.table) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val columns = remember(schema, search, keysOnly) {
        schema.columns.withIndex().filter { (_, column) ->
            (!keysOnly || column.primaryKeyOrder > 0) &&
                (column.name.contains(search, true) || column.typeName.contains(search, true))
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${schema.table.label} · 字段属性", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(search, { search = it.take(256) }, singleLine = true,
                label = { Text("查找字段名或类型") }, modifier = Modifier.fillMaxWidth().testTag("structure-search"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = keysOnly, onClick = { keysOnly = !keysOnly }, label = { Text("仅主键") })
                TextButton(onClick = { search = ""; keysOnly = false }) { Text("清除筛选") }
            }
            Text("显示 ${columns.size} / ${schema.columns.size} 个字段", style = MaterialTheme.typography.bodySmall)
            if (columns.isEmpty()) Text("没有匹配字段")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("structure-columns")) {
                itemsIndexed(columns, key = { _, item -> item.index }) { _, (index, column) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("structure-column-$index")) {
                        SelectionContainer { Text("${index + 1}. ${column.name}", style = MaterialTheme.typography.titleSmall) }
                        Text("${column.typeName} · 长度 / 精度 ${column.size.takeIf { it > 0 } ?: "未提供"} · 小数位 ${column.scale}", style = MaterialTheme.typography.bodySmall)
                        Text(buildList {
                            add(if (column.nullable) "允许 NULL" else "不允许 NULL")
                            if (column.primaryKeyOrder > 0) add("主键 #${column.primaryKeyOrder}")
                            if (column.identity) add("自增")
                            if (column.generated) add("计算 / 生成列")
                            if (column.hasDefault) add("有默认值")
                        }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(column.name)) }, modifier = Modifier.testTag("copy-column-$index")) { Text("复制字段名") }
                    }
                    HorizontalDivider()
                }
            }
            Text("属性来自当前表元数据；复制的是原始字段名。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
