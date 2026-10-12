package com.example.datasail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun KeyValueWorkbench(model: WorkspaceViewModel, state: WorkspaceUiState) {
    var pattern by remember { mutableStateOf(state.keys?.pattern ?: "*") }
    Text("${state.kind.label} · 键值工作台", style = MaterialTheme.typography.titleLarge)
    Text("只读浏览 · 使用 SCAN 游标；重复键和空页均可能出现，扫描不是快照。")
    OutlinedTextField(pattern, { pattern = it }, Modifier.fillMaxWidth(), label = { Text("键匹配模式（* 匹配全部）") }, singleLine = true)
    Row {
        Button(enabled = !state.busy && pattern.toByteArray().size <= 1024, onClick = { model.scanKeys("0", pattern) }) { Text("重新扫描") }
        state.keys?.let { page -> TextButton(enabled = !state.busy && page.cursor != "0" && pattern == page.pattern,
            onClick = { model.scanKeys(page.cursor, page.pattern) }) { Text("继续扫描") } }
    }
    state.keys?.let { page ->
        Text("本页 ${page.keys.size} 个键 · ${if (page.cursor == "0") "本轮扫描结束" else "还有游标页"}")
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 260.dp)) {
            items(page.keys) { key -> TextButton(enabled = !state.busy, onClick = { model.inspectKey(key) }) { Text(key.display, maxLines = 2) } }
        }
    }
    state.keyContent?.let { content ->
        Text(content.key.display, style = MaterialTheme.typography.titleMedium)
        Text("类型：${content.type} · 长度：${content.length} · TTL：${when (content.ttlMillis) { -2L -> "键不存在"; -1L -> "永不过期"; else -> "${content.ttlMillis} ms" }}")
        Text("类型、TTL 和内容分次读取；并发变更可能导致结果不同。字节值不能完整显示为文字时使用 hex。")
        if (content.limited) Text("仅预览前 2048 字节或前 100 项；内容未完整加载。")
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 320.dp)) {
            items(content.rows) { row -> Text(row.joinToString("  |  "), Modifier.padding(vertical = 6.dp)) }
        }
        Row {
            TextButton(enabled = !state.busy, onClick = { model.inspectKey(content.key) }) { Text("刷新内容") }
            if (content.cursor != "0") TextButton(enabled = !state.busy, onClick = { model.inspectKey(content.key, content.cursor) }) { Text("下一页内容") }
        }
    }
    Text("支持字符串、列表、哈希、集合、有序集合和 Stream。暂不提供写入、脚本、集群重定向或任意命令执行。单次响应限 256 KiB，超限将断开连接。", style = MaterialTheme.typography.bodySmall)
}
