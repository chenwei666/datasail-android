package com.example.datasail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun AiChatScreen(ai: AiViewModel, workspace: WorkspaceViewModel, openSettings: () -> Unit) {
    val state by ai.state.collectAsStateWithLifecycle()
    val database by workspace.state.collectAsStateWithLifecycle()
    val saved by workspace.saved.collectAsStateWithLifecycle()
    val allowed = remember(database, saved) { aiAllowed(workspace) && !database.busy }
    val profile = state.preferences.active
    var prompt by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    val scope = aiScope(workspace)
    LaunchedEffect(scope) { ai.cancel() }
    DisposableEffect(Unit) { onDispose { ai.cancel() } }
    LaunchedEffect(state.chat) {
        if (sent.isNotEmpty() && state.chat.lastOrNull()?.question == sent && prompt == sent) prompt = ""
        sent = ""
    }
    Text("和 AI 聊聊", style = MaterialTheme.typography.headlineSmall)
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(profile?.let { "${it.name} · ${it.model.ifBlank { "未选择模型" }}" } ?: "尚未配置服务", style = MaterialTheme.typography.titleMedium)
        profile?.let { Text(it.endpoint, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = openSettings) { Text("前往 AI 设置") }
        if (!state.preferences.enabled || profile?.model.isNullOrBlank()) Text("请在设置中启用 AI，并保存服务和模型。")
        Text("发送会将输入及最近对话交给此服务；最多保留 8 轮、发送 24000 字符，超限从最早整轮移除。仅保留在内存中，不自动附加数据库内容。", style = MaterialTheme.typography.bodySmall)
    } }
    if (!allowed) Text(if (database.busy) "数据库正在切换，请等待完成。" else "此连接禁止外部 AI。可在连接配置中显式允许，重新连接后生效。", color = MaterialTheme.colorScheme.error)
    state.chat.forEachIndexed { index, turn ->
        Card(Modifier.fillMaxWidth().testTag("chat-question-$index")) { Column(Modifier.padding(12.dp)) { Text("你", style = MaterialTheme.typography.labelMedium); SelectionContainer { Text(turn.question) } } }
        OutlinedCard(Modifier.fillMaxWidth().testTag("chat-answer-$index")) { Column(Modifier.padding(12.dp)) { Text("AI", style = MaterialTheme.typography.labelMedium); SelectionContainer { Text(turn.answer) } } }
    }
    OutlinedTextField(prompt, { if (it.length <= 12000) prompt = it }, modifier = Modifier.fillMaxWidth().testTag("ai-chat-input"),
        label = { Text("发消息，或继续追问") }, minLines = 2, maxLines = 6, enabled = !state.busy)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { sent = prompt; ai.chat(workspace, prompt) }, enabled = allowed && state.ready && !state.busy && state.preferences.enabled && profile?.model?.isNotBlank() == true && prompt.isNotBlank()) { Text("发送") }
        if (state.busy) OutlinedButton(onClick = ai::cancel) { Text("取消请求") }
        else TextButton(onClick = { ai.clearChat(); prompt = ""; sent = "" }) { Text("清空对话") }
    }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(state.message, style = MaterialTheme.typography.bodySmall)
    Text("需要查询或修改数据库，请选择「操作当前表」；聊天不会执行数据库操作。", style = MaterialTheme.typography.bodySmall)
}
