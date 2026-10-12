package com.example.datasail

import androidx.lifecycle.ViewModel
import com.example.datasail.core.ConnectionProfile
import com.example.datasail.core.Credentials
import com.example.datasail.core.ExecutionState
import com.example.datasail.core.ProbeMode
import com.example.datasail.core.ProbeReport
import com.example.datasail.core.ProbeRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ProbeUiState(val execution: ExecutionState = ExecutionState.IDLE, val report: ProbeReport? = null, val target: String? = null) {
    val busy get() = execution in setOf(ExecutionState.CONNECTING, ExecutionState.RUNNING, ExecutionState.CANCELLING)
}
class ProbeViewModel : ViewModel() {
    private val runner = ProbeRunner()
    private val mutableState = MutableStateFlow(ProbeUiState())
    val state = mutableState.asStateFlow()
    fun start(profile: ConnectionProfile, password: CharArray, mode: ProbeMode) {
        val credentials = Credentials(password)
        password.fill('\u0000')
        val target = "${profile.kind.label} / ${profile.host}:${profile.port} / ${profile.database} · 测试 / 固定只读 / Schema 不适用"
        runner.start(profile, credentials, mode,
            update = { execution -> mutableState.update { ProbeUiState(execution, target = target) } },
            complete = { report -> mutableState.value = ProbeUiState(report.state, report, target) })
    }
    fun cancel() = runner.cancel()
    override fun onCleared() { runner.close() }
}
