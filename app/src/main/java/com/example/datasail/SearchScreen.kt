package com.example.datasail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun SearchWorkbench(model: WorkspaceViewModel, state: WorkspaceUiState) {
    var selected by remember(state.target, state.catalog) { mutableStateOf("") }
    var query by remember(state.target, state.catalog) { mutableStateOf("{\"match_all\":{}}") }
    var filter by remember { mutableStateOf("") }
    Text("索引与搜索", style = MaterialTheme.typography.titleLarge)
    Text("只读 · 范围 ${state.catalog} · 每页 100 条，最多浏览前 1000 条", style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = !state.busy, onClick = { selected = ""; model.indices() }) { Text("刷新索引") }
    OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("搜索索引名称") }, singleLine = true)
    LazyColumn(Modifier.testTag("search-indices").fillMaxWidth().heightIn(max = 200.dp)) {
        items(state.indices.filter { it.contains(filter, true) }) { name ->
            TextButton(enabled = !state.busy, onClick = { selected = name; query = "{\"match_all\":{}}"; model.search(name, query) }) { Text(name) }
        }
    }
    if (state.indices.isEmpty()) Text("指定范围内没有可浏览的索引")
    if (selected.isNotEmpty()) {
        Text("当前索引：$selected")
        OutlinedTextField(query, { if (it.length <= 8000) query = it }, Modifier.fillMaxWidth(), label = { Text("查询条件 DSL") }, minLines = 2, maxLines = 5)
        Text("输入 query 内部条件，例如 {\"term\":{\"code\":\"0007\"}}。支持 match_all、term、match、range、bool。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.busy, onClick = { model.search(selected, query) }) { Text("重新搜索") }
            OutlinedButton(enabled = !state.busy, onClick = { model.mapping(selected) }) { Text("查看映射") }
        }
    }
    state.mapping?.let { mapping ->
        Text("索引映射：${mapping.index}", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) { item { SelectionContainer { Text(mapping.json, fontFamily = FontFamily.Monospace) } } }
    }
    state.searchPage?.let { page ->
        Text("结果：${page.index} · 第 ${page.offset / 100 + 1} 页 · ${page.hits.size} 条", style = MaterialTheme.typography.titleMedium)
        Text("当前条件：${page.query}", style = MaterialTheme.typography.bodySmall)
        Text("分页不是一致性快照；索引变化可能造成重复或遗漏。JSON 保留数值原文，大字段标记未展开。", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(page.hits) { hit -> OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) { Text("ID：${hit.id}"); SelectionContainer { Text(hit.json ?: hit.unavailable.orEmpty(), fontFamily = FontFamily.Monospace) } }
            } }
        }
        Button(enabled = !state.busy && page.hasMore && selected == page.index && query == page.query,
            onClick = { model.search(page.index, page.query, page.offset + 100) }) { Text("下一页搜索结果") }
        if (page.windowLimit) Text("已达 1000 条浏览上限，请缩小查询范围")
        if (selected != page.index || query != page.query) Text("条件已改变，请重新搜索后翻页")
    }
}
