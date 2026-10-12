package com.example.datasail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun DocumentWorkbench(model: WorkspaceViewModel, state: WorkspaceUiState) {
    var selected by remember(state.target, state.catalog) { mutableStateOf("") }
    var filter by remember(state.target, state.catalog) { mutableStateOf("{}") }
    var search by remember { mutableStateOf("") }
    Text("集合与文档", style = MaterialTheme.typography.titleLarge)
    Text("只读 · 每页最多 100 个文档 · 保留 BSON 类型的 Extended JSON", style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = !state.busy, onClick = model::collections) { Text("刷新集合") }
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜索集合名称") }, singleLine = true)
    LazyColumn(Modifier.testTag("mongo-collections").fillMaxWidth().heightIn(max = 200.dp)) {
        itemsIndexed(state.collections.filter { it.contains(search, true) }) { _, name ->
            TextButton(enabled = !state.busy, onClick = { selected = name; filter = "{}"; model.findDocuments(name, "{}") }) { Text(name) }
        }
    }
    if (state.collections.isEmpty()) Text("此账号没有可浏览的集合")
    if (selected.isNotEmpty()) {
        Text("筛选集合：$selected")
        OutlinedTextField(filter, { if (it.length <= 8000) filter = it }, Modifier.fillMaxWidth(), label = { Text("文档筛选 JSON") }, minLines = 2, maxLines = 5)
        Text("示例：{\"name\":\"0007\"}。支持比较、包含、存在与逻辑条件；不执行脚本。", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !state.busy, onClick = { model.findDocuments(selected, filter) }) { Text("重新查询文档") }
    }
    state.documents?.let { page ->
        Text("结果：${page.collection} · 第 ${page.page} 页 · ${page.documents.size} 个文档", style = MaterialTheme.typography.titleMedium)
        Text("当前结果筛选：${page.filter}", style = MaterialTheme.typography.bodySmall)
        Text("游标顺序不是一致性快照；数据库变化可能影响后续页。大文档显示未展开标记；游标过期需重新查询。", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(page.documents) { index, document -> OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("文档 ${(page.page - 1) * 100 + index + 1}")
                    SelectionContainer { Text(document.json ?: document.unavailable.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            } }
        }
        Button(enabled = !state.busy && page.hasMore && selected == page.collection && filter == page.filter, onClick = model::nextDocuments) { Text("下一页文档") }
        if (selected != page.collection || filter != page.filter) Text("筛选已改变，请重新查询后翻页")
    }
}
