package com.example.datasail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun AiConversationView(state: AiUiState) {
    Text("对话记忆 · 本机加密 · 按模型和数据库隔离", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val list = rememberLazyListState()
    LaunchedEffect(state.historyKey, state.chat.size) { if (state.chat.isNotEmpty()) list.scrollToItem(state.chat.lastIndex) }
    if (state.chat.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp).testTag("ai-conversation"), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(state.chat) { index, turn ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(Modifier.fillMaxWidth().padding(start = 28.dp).testTag("chat-question-$index")) { Column(Modifier.padding(12.dp)) {
                    Text("你", style = MaterialTheme.typography.labelSmall)
                    SelectionContainer { Text(turn.question, style = MaterialTheme.typography.bodyMedium) }
                } }
                OutlinedCard(Modifier.fillMaxWidth().padding(end = 28.dp).testTag("chat-answer-$index")) { Column(Modifier.padding(12.dp)) {
                    Text("AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(turn.answer, style = MaterialTheme.typography.bodyMedium) }
                } }
            }
        }
    }
}
