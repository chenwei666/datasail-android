package com.example.datasail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun SettingsScreen(model: WorkspaceViewModel, ai: AiViewModel, openAi: () -> Unit, diagnostics: @Composable () -> Unit) {
    val aiState by ai.state.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val count by model.passwordCount.collectAsStateWithLifecycle()
    var clearPasswords by remember { mutableStateOf(false) }
    var resetSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    Text("设置", style = MaterialTheme.typography.headlineSmall)
    SettingsGroup("AI 服务与模型") {
        Text(aiState.preferences.active?.let { "${it.name} · ${it.model.ifBlank { "未选择模型" }}" } ?: "尚未配置 AI 服务")
        Text(if (aiState.preferences.enabled) "AI 已开启" else "AI 已关闭", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = openAi) { Text("配置 AI 服务与模型") }
        Text("服务地址、密钥、模型获取和切换统一在这里管理。", style = MaterialTheme.typography.bodySmall)
    }
    SettingsGroup("外观") {
        Text("主题", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode -> FilterChip(selected = settings.theme == mode,
                onClick = { model.updateSettings(settings.copy(theme = mode)) }, label = { Text(when(mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) }) }
        }
    }
    SettingsGroup("数据表格") {
        SettingsSwitch("紧凑行高", settings.compactGrid) { model.updateSettings(settings.copy(compactGrid = it)) }
        SettingsSwitch("显示列类型", settings.showColumnTypes) { model.updateSettings(settings.copy(showColumnTypes = it)) }
        SettingsSwitch("单元格换行", settings.wrapCells) { model.updateSettings(settings.copy(wrapCells = it)) }
        Text("默认列宽")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(140,200,280,360).forEach { width ->
            FilterChip(selected = settings.columnWidth == width, onClick = { model.updateSettings(settings.copy(columnWidth = width)) }, label = { Text(width.toString()) })
        } }
        FontSetting("表格字号", settings.gridFont) { model.updateSettings(settings.copy(gridFont = it)) }
    }
    SettingsGroup("SQL 编辑器") {
        SettingsSwitch("SQL 补全", settings.completion) { model.updateSettings(settings.copy(completion = it)) }
        Text("本地关键字、已加载的表和字段；外接键盘 Tab 或编辑器补全按钮。", style = MaterialTheme.typography.bodySmall)
        FontSetting("编辑器字号", settings.editorFont) { model.updateSettings(settings.copy(editorFont = it)) }
    }
    SettingsGroup("连接与密码") {
        SettingsSwitch("新连接默认为生产环境", settings.defaultProduction) { model.updateSettings(settings.copy(defaultProduction = it)) }
        Text("连接默认只读；SQL Server 可明确开启本次编辑。此默认值只影响新连接的环境标识。", style = MaterialTheme.typography.bodySmall)
        Text("已保存密码：$count 个连接")
        Text("密码仅存于此设备的加密存储，不进入连接配置、备份或导出；系统密钥丢失时需重新输入。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { clearPasswords = true }) { Text("清除全部保存的密码") }
        Text("后台断开连接 · 禁止截屏 · 默认校验服务器证书", style = MaterialTheme.typography.bodySmall)
    }
    SettingsGroup("本次工作状态") {
        Text("草稿、表格布局、筛选和最近 30 条成功查询仅保存在进程内；关闭进程后清除。不保留数据行或待提交写入。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = model::clearWorkspaceMemory) { Text("清除本次工作状态与历史") }
    }
    SettingsGroup("关于数舟") {
        Text("DataSail ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("本地直连数据库 · AI 辅助查询与编辑")
        TextButton(onClick = { resetSettings = true }) { Text("恢复默认设置") }
        TextButton(onClick = { showDiagnostics = !showDiagnostics }) { Text(if (showDiagnostics) "收起开发诊断" else "开发诊断与数据库支持") }
        if (showDiagnostics) diagnostics()
    }
    if (clearPasswords) AlertDialog(onDismissRequest = { clearPasswords = false }, title = { Text("清除所有保存的密码？") },
        text = { Text("连接、收藏和证书信任保留。下次连接需要重新输入密码。") },
        confirmButton = { TextButton(onClick = { model.forgetPassword(); clearPasswords = false }) { Text("确认清除密码") } },
        dismissButton = { TextButton(onClick = { clearPasswords = false }) { Text("取消") } })
    if (resetSettings) AlertDialog(onDismissRequest = { resetSettings = false }, title = { Text("恢复默认设置？") },
        text = { Text("恢复外观、表格、编辑器和新连接默认值，保留连接、密码和收藏。") },
        confirmButton = { TextButton(onClick = { model.updateSettings(AppSettings()); resetSettings = false }) { Text("确认恢复设置") } },
        dismissButton = { TextButton(onClick = { resetSettings = false }) { Text("取消") } })
}
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    } }
}
@Composable
private fun SettingsSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(value, change, modifier = Modifier.semantics { contentDescription = label })
    }
}
@Composable
private fun FontSetting(label: String, value: Int, change: (Int) -> Unit) {
    Text("$label · $value sp")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(12,14,16,18,20).forEach { size -> FilterChip(selected = size == value, onClick = { change(size) }, label = { Text(size.toString()) }) }
    }
}
