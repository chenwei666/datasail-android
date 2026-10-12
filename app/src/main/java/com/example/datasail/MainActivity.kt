package com.example.datasail

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.datasail.core.*

class MainActivity : ComponentActivity() {
    private val model: ProbeViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                DataSail(model)
            }
        }
    }
    override fun onStop() {
        // No background session: rotation/background transitions request cancellation.
        model.cancel()
        super.onStop()
    }
}

@Composable
private fun DataSail(model: ProbeViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf("连接") }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("连接", "工作台", "AI 助手", "收藏", "设置").forEach { title ->
                val available = title == "连接" || title == "设置"
                NavigationBarItem(selected = page == title, onClick = { page = title }, enabled = available,
                    icon = { Text(if (available) "●" else "—") }, label = { Text(title) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("数舟", style = MaterialTheme.typography.headlineLarge)
            Text("DataSail · V0.1.0 技术验证", style = MaterialTheme.typography.labelLarge)
            if (state.busy) Text("正在验证：${state.target}\n${state.execution.label()}")
            if (page == "设置") {
                Text("开发占位包名：com.example.datasail\n首次发行前需确认正式身份与签名。")
                Text("Android 8.0 为最低构建目标，Android 11 为重点验收目标；构建成功不等于真机兼容。")
                Text("凭据不持久化；AI 未启用。应用进入后台会请求取消连接/查询，不自动重连。")
                Text("SQL Server：mssql-jdbc 13.6.0.jre8（MIT）\nMySQL：MariaDB Connector/J 3.5.10（LGPL-2.1-or-later）\n两者均为实验性接入，详见源码 docs/DRIVER_MATRIX.md。")
                Text("工作台、AI 助手、收藏尚未实现，对应入口已禁用。")
            } else ProbeForm(model, state)
        }
    }
}

@Composable
private fun ProbeForm(model: ProbeViewModel, state: ProbeUiState) {
    var kind by remember { mutableStateOf(DatabaseKind.SQL_SERVER) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("1433") }
    var database by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    // Intentionally not rememberSaveable: credentials must never enter saved instance state.
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ProbeMode.BASIC) }
    var authorized by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("数据库驱动验证", style = MaterialTheme.typography.titleLarge)
    Text("仅测试环境 · 固定只读探针 · Schema：不适用\n仅连接你明确授权的隔离测试库，不访问业务表。")
    DatabaseKind.entries.forEach { option ->
        FilterChip(selected = kind == option, enabled = !state.busy, onClick = {
            kind = option; port = option.defaultPort.toString(); authorized = false
        }, label = { Text(option.label) })
    }
    OutlinedTextField(host, { host = it; authorized = false }, label = { Text("服务器 DNS 名称 / IPv4") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(port, { port = it; authorized = false }, label = { Text("端口") }, enabled = !state.busy, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(database, { database = it; authorized = false }, label = { Text("测试数据库") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(username, { username = it; authorized = false }, label = { Text("测试用户名") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(password, { password = it }, label = { Text("密码（不保存）") }, enabled = !state.busy, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    Text("TLS：强制加密 + 主机名/证书验证。当前仅使用系统信任链，不提供跳过验证开关。")
    ProbeMode.entries.forEach { option ->
        FilterChip(selected = mode == option, enabled = !state.busy, onClick = { mode = option }, label = { Text(option.label) })
    }
    Row {
        Checkbox(checked = authorized, onCheckedChange = { authorized = it }, enabled = !state.busy)
        Text("我确认这是已授权的隔离测试库，允许执行固定合成查询及最长 10 秒的延迟探针。", modifier = Modifier.padding(top = 8.dp))
    }
    Text("待测目标：${kind.label} / ${host.ifBlank { "未填写" }}:$port / ${database.ifBlank { "未选择库" }}\n状态：${state.execution.label()}")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(enabled = authorized && !state.busy && password.isNotEmpty(), onClick = {
            try {
                val profile = ConnectionProfile(kind, host.trim(), port.toIntOrNull() ?: 0, database.trim(), username)
                error = null
                val secret = password.toCharArray()
                password = ""
                model.start(profile, secret, mode)
            } catch (_: IllegalArgumentException) {
                error = "请检查主机、端口、库名和用户名；主机不可填 URL，库名仅支持字母、数字、下划线和连字符。"
            }
        }) { Text("运行探针") }
        OutlinedButton(enabled = state.busy && state.execution != ExecutionState.CANCELLING, onClick = { model.cancel() }) { Text("取消 / 断开") }
    }
    if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    state.report?.let { report ->
        HorizontalDivider()
        Text("本次结果", style = MaterialTheme.typography.titleMedium)
        Text("本次结果对应目标：${state.target}")
        report.failure?.let { Text("${it.description}（${it.kind} / ${it.sqlState ?: "无 SQLState"} / ${it.vendorCode ?: 0}）") }
        report.checks.forEach { check ->
            Text("${check.status.label()} · ${check.name}\n${check.detail}")
        }
        if (report.state == ExecutionState.UNKNOWN) Text("执行结果未知，需核对；不会自动重放操作。")
    }
    Text("实验性驱动。一次连接成功不代表已验证所有 Android 版本、服务器版本或证书异常。", style = MaterialTheme.typography.bodySmall)
}
private fun ExecutionState.label(): String = when (this) {
    ExecutionState.IDLE -> "未连接"
    ExecutionState.CONNECTING -> "连接中"
    ExecutionState.RUNNING -> "验证中"
    ExecutionState.CANCELLING -> "正在请求取消，等待清理"
    ExecutionState.SUCCEEDED -> "本次探针通过，连接已关闭"
    ExecutionState.FAILED -> "本次探针失败"
    ExecutionState.UNKNOWN -> "执行结果未知，需核对"
}
private fun CheckStatus.label(): String = when (this) {
    CheckStatus.PASSED -> "通过"
    CheckStatus.FAILED -> "失败"
    CheckStatus.NOT_RUN -> "未执行"
    CheckStatus.UNKNOWN -> "未知"
}
