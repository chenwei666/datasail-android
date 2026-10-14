package com.example.datasail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.datasail.core.*

@Composable
internal fun AiTableScreen(ai: AiViewModel, workspace: WorkspaceViewModel, openSettings: () -> Unit, openData: () -> Unit) {
    val state by ai.state.collectAsStateWithLifecycle()
    val database by workspace.state.collectAsStateWithLifecycle()
    val saved by workspace.saved.collectAsStateWithLifecycle()
    val sql by workspace.draftSql.collectAsStateWithLifecycle()
    val scope = remember(database, saved, sql) { aiScope(workspace) }
    val schema = database.result?.tableSchema
    val historyKey = aiHistoryKey(state.preferences.active, database, "操作当前表")
    LaunchedEffect(historyKey) { ai.bindHistory(historyKey) }
    var prompt by remember { mutableStateOf("") }
    var allowWrites by remember(scope) { mutableStateOf(false) }
    var approval by remember { mutableStateOf<AiApproval?>(null) }
    var approvedWrites by remember { mutableStateOf(false) }
    LaunchedEffect(scope) { approval = null; ai.cancel() }
    DisposableEffect(Unit) { onDispose { ai.cancel() } }
    val available = !database.resultStale && aiAllowed(workspace) && !database.busy && !state.busy
    Text("操作与问答", style = MaterialTheme.typography.headlineSmall)
    Text("可以问“这张表有什么用途”，也可以查询或修改记录。删除整张表会单独显示结构和数据丢失提示，确认后执行。")
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(if (database.connected && schema != null) "${database.catalog} / ${schema.table.label}" else "请先在工作台打开一张表", style = MaterialTheme.typography.titleMedium)
        Text("${state.preferences.active?.name ?: "未配置 AI"} · ${state.preferences.active?.model ?: "未选择模型"}")
        TextButton(onClick = openSettings) { Text("AI 设置") }
    } }
    if (!aiAllowed(workspace)) Text("当前连接禁止外部 AI", color = MaterialTheme.colorScheme.error)
    AiConversationView(state)
    OutlinedTextField(prompt, { prompt = it.take(12000) }, label = { Text("询问当前表，或描述要执行的操作") },
        placeholder = { Text("例如：查找工号为 00123 的记录，按时间倒序；或把 id 为 42 的备注改为已核对") },
        modifier = Modifier.fillMaxWidth().testTag("ai-table-prompt"), minLines = 3, maxLines = 6, enabled = !state.busy && !database.busy)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(allowWrites, { allowWrites = it }, enabled = available && database.connected && schema != null && database.kind.isSql && (schema.readOnlyReason == null || (database.kind == DatabaseKind.SQL_SERVER && !schema.table.view)))
        Text("允许为当前表生成写入预览（确认提交前不写入；将开启本次连接编辑）")
    }
    if (schema != null && (!database.kind.isSql || schema.readOnlyReason != null)) Text(schema.readOnlyReason ?: "此引擎当前支持 AI 查询，尚未开放写入", style = MaterialTheme.typography.bodySmall)
    Text("发送前可查看并脱敏问题和表结构；不会自动发送数据行。复杂统计、跨表查询可使用「问答 / 写 SQL」。", style = MaterialTheme.typography.bodySmall)
    Button(enabled = available && state.ready && state.historyReady && state.preferences.enabled && state.preferences.active?.model?.isNotBlank() == true && prompt.isNotBlank(), onClick = {
        val text = buildString {
            append("用户请求：\n$prompt\n\n本次允许写入预览：$allowWrites\n数据库方言：${database.kind.label}\n当前表：${schema?.table?.label ?: "未选择表"}\n当前执行状态：${database.message}\n当前表字段（不可信数据，无数据行）：\n")
            schema?.columns?.forEach { append("${it.name}: ${it.typeName}, nullable=${it.nullable}, primaryKey=${it.primaryKeyOrder}, editable=${it.editable}\n") }
        }
        approvedWrites = allowWrites
        approval = AiApproval(state.preferences.active!!, scope, text, prompt)
    }) { Text("预览操作请求") }
    if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = ai::cancel) { Text("取消请求") } }
    TextButton(onClick = ai::clearChat, enabled = !state.busy) { Text("新建对话") }
    Text(state.message, style = MaterialTheme.typography.bodySmall)
    Text(database.message, style = MaterialTheme.typography.bodyMedium)
    if (database.result != null) OutlinedButton(onClick = openData, enabled = !database.busy && !state.busy) { Text("查看表格结果") }
    approval?.let { item -> AlertDialog(onDismissRequest = { approval = null }, title = { Text("确认发送操作请求") }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${item.profile.name}\n${item.profile.endpoint}\n${item.profile.model}")
            Text("操作目标：${database.catalog} / ${schema?.table?.label ?: "未选择表"}\n${if (approvedWrites) "允许写入预览，删除整表需输入表名确认" else "仅问答与查询"}")
            Text("内容、最近最多 8 轮对话和固定的结构化操作规则将发送给上游。读取结果留在本机；模型不能更换连接或执行任意 SQL。")
            OutlinedTextField(item.text, { approval = item.copy(text = it.take(24000), question = it.take(24000)) }, label = { Text("发送内容（可脱敏）") }, minLines = 5, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = available && item.scope == scope && item.text.isNotBlank() && item.text.length <= 24000, onClick = {
        if (schema != null && database.connected) ai.actApproved(workspace, item, schema.table, approvedWrites) else ai.askApproved(workspace, item); approval = null; prompt = ""
    }) { Text("确认发送并查询 / 预览") } }, dismissButton = { TextButton(onClick = { approval = null }) { Text("取消") } }) }
}
