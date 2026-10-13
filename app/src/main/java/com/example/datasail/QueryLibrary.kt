package com.example.datasail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun QueryLibrary(model: WorkspaceViewModel, state: WorkspaceUiState, openWorkbench: () -> Unit) {
    val tables by model.favoriteTables.collectAsStateWithLifecycle()
    val queries by model.queries.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SavedQuery?>(null) }
    var tableSearch by remember { mutableStateOf("") }
    Text("收藏的表", style = MaterialTheme.typography.titleLarge)
    Text("收藏保存在本机。连接原服务器账号后，一键打开表；不会自动开启写入。")
    OutlinedTextField(tableSearch, { tableSearch = it }, Modifier.fillMaxWidth(), label = { Text("搜索收藏的表 / 数据库") }, singleLine = true)
    if (tables.isEmpty()) Text("打开表后点击“收藏此表”，下次可从这里打开。")
    tables.filter { "${it.name} ${it.catalog} ${it.schema} ${it.connectionName}".contains(tableSearch, true) }.forEach { table ->
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(table.name, style = MaterialTheme.typography.titleMedium)
            Text("${table.connectionName} · ${table.kind.label} / ${table.catalog} / ${table.schema}")
            val sameConnection = state.connected && state.connectionKey == table.connectionKey
            if (!sameConnection) Text("请先在连接页连接此收藏的原连接")
            Row {
                TextButton(enabled = sameConnection && !state.busy, onClick = { if (model.openTableFavorite(table)) openWorkbench() }) { Text("打开收藏表") }
                TextButton(onClick = { model.removeTableFavorite(table) }) { Text("取消收藏此表") }
            }
        } }
    }
    HorizontalDivider()
    Text("常用查询", style = MaterialTheme.typography.titleLarge)
    Text("收藏保存在本机。连接同类型数据库后可载入草稿，检查目标后手动执行。")
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜索查询名称 / 引擎") }, singleLine = true)
    if (queries.isEmpty()) Text("在 SQL 工作台点击“收藏查询”，保存常用 SELECT。")
    queries.filter { it.name.contains(search, true) || it.kind.label.contains(search, true) }.forEach { query ->
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text("${query.name} · ${query.kind.label}", style = MaterialTheme.typography.titleMedium)
            Text(query.sql, maxLines = 6)
            Row {
                TextButton(enabled = state.connected && state.kind == query.kind && !state.busy, onClick = { if (model.useQuery(query)) openWorkbench() }) { Text("载入草稿") }
                TextButton(onClick = { deleting = query }) { Text("删除收藏") }
            }
        } }
    }
    deleting?.let { query -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除收藏？") }, text = { Text(query.name) },
        confirmButton = { TextButton(onClick = { model.deleteQuery(query.id); deleting = null }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
