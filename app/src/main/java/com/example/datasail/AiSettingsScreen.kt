package com.example.datasail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.datasail.core.*

@Composable
internal fun AiSettingsScreen(ai: AiViewModel, back: () -> Unit, openAssistant: () -> Unit) {
    val state by ai.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(onBack = back)
    DisposableEffect(Unit) { onDispose { ai.cancel() } }
    TextButton(onClick = back) { Text("返回设置") }
    Text("AI 服务与模型", style = MaterialTheme.typography.headlineSmall)
    Text("选择服务 → 填写密钥 → 获取并选择模型。配置统一保存在这里。", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("启用 AI 助手", Modifier.weight(1f))
        Switch(state.preferences.enabled, { ai.preferences(state.preferences.copy(enabled = it)) }, enabled = state.ready && !state.busy, modifier = Modifier.testTag("ai-enabled"))
    }
    AiConfiguration(ai, state)
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = ai::cancel) { Text("取消请求") }
    }
    Text(state.message, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = openAssistant) { Text("打开 AI 助手") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiConfiguration(ai: AiViewModel, state: AiUiState) {
    var draft by remember(state.preferences.active) { mutableStateOf(state.preferences.active ?: AiProfile()) }
    var key by remember(draft.id, draft.endpoint, draft.protocol) { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("连接模型服务", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.preferences.profiles.forEach { profile -> FilterChip(selected = state.preferences.activeId == profile.id, enabled = !state.busy, onClick = { ai.preferences(state.preferences.copy(activeId = profile.id)) }, label = { Text(profile.name) }) }
            AssistChip(onClick = { presets = true }, enabled = !state.busy, label = { Text("添加服务") })
        }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级配置" else "高级配置：名称与接口协议") }
        if (advanced) {
        OutlinedTextField(draft.name, { if (it.length <= 80) draft = draft.copy(name = it) }, label = { Text("服务名称") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { AiProtocol.entries.forEach { protocol -> FilterChip(selected = draft.protocol == protocol, enabled = !state.busy,
            onClick = { draft = draft.copy(protocol = protocol, endpoint = protocol.defaultEndpoint, model = "") }, label = { Text(protocol.label) }) } }
        }
        val endpointValid = runCatching { draft.service.base }.isSuccess
        OutlinedTextField(draft.endpoint, { if (it.length <= 2048) draft = draft.copy(endpoint = it) }, label = { Text("HTTPS API 基础地址（含版本路径）") }, isError = !endpointValid, supportingText = { if (!endpointValid) Text("请输入有效的 HTTPS API 地址；不可包含账号、密码或查询参数") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("ai-endpoint"))
        OutlinedTextField(key, { if (it.length <= 4096) key = it }, label = { Text("API 密钥") }, supportingText = { Text("已保存的密钥可留空保留；更换地址后请重新输入。") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("ai-key"))
        OutlinedTextField(draft.model, { if (it.length <= 256) draft = draft.copy(model = it) }, label = { Text("模型 ID / 部署 ID") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("ai-model"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = state.ready && !state.busy && endpointValid && draft.name.isNotBlank(), onClick = { ai.save(draft, key.trim().toCharArray(), fetchModels = true); key = "" }, modifier = Modifier.testTag("ai-fetch-models")) { Text(if (state.busy) "正在获取…" else "获取上游模型") }
            OutlinedButton(enabled = state.ready && !state.busy, onClick = { ai.save(draft, key.trim().toCharArray()); key = "" }) { Text("保存服务") }
        }
        if (state.models.isNotEmpty() && runCatching { draft.service.binding }.getOrNull() == state.modelsBinding) OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { filter = ""; models = true }) { Text("选择模型（${state.models.size}）") }
        Text("点击获取会先保存当前地址和密钥，无需先填模型 ID，也无需先开启 AI。取得列表后选择模型即可保存；上游不提供列表时可手填 ID 后保存。", style = MaterialTheme.typography.bodySmall)
        Text("兼容文本生成接口；预设不代表服务商已实测。AWS Bedrock / Vertex 云 IAM、Azure 专有认证、图片与音频接口暂未接入。", style = MaterialTheme.typography.bodySmall)
        state.preferences.active?.let { active -> Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(enabled = !state.busy, onClick = { ai.forgetKey(active) }) { Text("清除此服务密钥") }
            TextButton(enabled = !state.busy, onClick = { ai.remove(active) }) { Text("删除服务") }
        } }
    } }
    if (presets) AlertDialog(onDismissRequest = { presets = false }, title = { Text("国内外模型服务") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) { items(aiPresets) { preset -> TextButton(onClick = { draft = AiProfile(name = preset.name, protocol = preset.protocol, endpoint = preset.endpoint); key = ""; presets = false }) { Text(preset.name) } } }
    }, confirmButton = { TextButton(onClick = { presets = false }) { Text("关闭") } })
    LaunchedEffect(state.models, state.modelsBinding) { if (state.models.isNotEmpty() && runCatching { draft.service.binding }.getOrNull() == state.modelsBinding) { filter = ""; models = true } }
    if (models) AlertDialog(onDismissRequest = { models = false }, title = { Text("选择上游模型") }, text = {
        Column { OutlinedTextField(filter, { filter = it.take(256) }, label = { Text("筛选模型") }); LazyColumn(Modifier.heightIn(max = 350.dp)) {
            items(state.models.filter { it.contains(filter, true) }) { name -> TextButton(onClick = { draft = draft.copy(model = name); ai.save(draft, key.trim().toCharArray()); key = ""; models = false }) { Text(name) } }
        } }
    }, confirmButton = { TextButton(onClick = { models = false }) { Text("关闭") } })
}
