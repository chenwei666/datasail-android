package com.example.datasail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.datasail.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal data class AiUiState(val ready: Boolean = false, val busy: Boolean = false, val preferences: AiPreferences = AiPreferences(),
    val inlineSql: String = "", val inlineBinding: String = "", val models: List<String> = emptyList(), val modelsBinding: String = "", val answer: String = "", val answerScope: String = "",
    val message: String = "配置服务后即可使用；数据库功能无需启用 AI。")
internal class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AiStore(application)
    private val vault = PasswordVault(application, "ai-keys-v1")
    private val mutable = MutableStateFlow(AiUiState())
    val state = mutable.asStateFlow()
    private var cancellation = AtomicBoolean(false)
    private val clientLock = Any()
    private var client: AiClient? = null
    internal var createClient: (AiService, CharArray) -> AiClient = { service, secret -> AiClient(service, secret) }
    init { viewModelScope.launch {
        try { mutable.value = mutable.value.copy(preferences = withContext(Dispatchers.IO) { store.load() }, ready = true) }
        catch (_: Exception) { mutable.value = mutable.value.copy(message = "AI 配置读取失败，已禁止覆盖；数据库仍可使用") }
    } }
    fun save(profile: AiProfile, key: CharArray) {
        if (!state.value.ready || state.value.busy) { key.fill('\u0000'); return }
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val next = state.value.preferences.copy(activeId = profile.id, profiles = state.value.preferences.profiles.filterNot { it.id == profile.id } + profile)
                withContext(Dispatchers.IO) {
                    profile.service.base
                    require(key.size <= 4096 && key.all { it.code in 33..126 })
                    // Saving metadata first cannot leak a key: ciphertext remains bound to its exact old endpoint.
                    store.save(next)
                    if (key.isNotEmpty()) vault.saveBound(profile.id, profile.binding, key)
                }
                mutable.value = mutable.value.copy(preferences = next, models = emptyList(), answer = "", message = "服务已保存；空密钥保留同地址原密钥，修改地址后需重新填写")
            } catch (_: Exception) {
                val persisted = withContext(Dispatchers.IO) { runCatching { store.load() }.getOrNull() }
                mutable.value = mutable.value.copy(preferences = persisted ?: mutable.value.preferences, ready = persisted != null, message = "服务或密钥保存失败，请检查 HTTPS 基础地址并重新填写密钥")
            }
            finally { key.fill('\u0000'); mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun preferences(value: AiPreferences) {
        if (!state.value.ready || state.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.save(value) }; mutable.value = mutable.value.copy(preferences = value, models = emptyList(), answer = "", message = "AI 设置已保存") }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "AI 设置保存失败") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun forgetKey(profile: AiProfile) {
        if (!state.value.ready || state.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { vault.delete(profile.id) }; mutable.value = mutable.value.copy(message = "此服务密钥已清除") }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "清除密钥失败") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun models() = request { profile, transport ->
        val result = transport.models();
        { mutable.value = mutable.value.copy(models = result.names, modelsBinding = profile.service.binding,
            message = "取得 ${result.names.size} 个模型${if (result.limited) "（达到分页上限，可手填其它 ID）" else ""}；是否可调用取决于账号权限") }
    }
    fun askApproved(workspace: WorkspaceViewModel, approval: AiApproval) {
        if (workspace.state.value.busy || !aiAllowed(workspace) || aiScope(workspace) != approval.scope) return
        ask(approval.profile, approval.scope, approval.text)
    }
    fun completeSql(workspace: WorkspaceViewModel, binding: String, text: String) {
        if (!aiAllowed(workspace) || workspace.state.value.busy) return
        if (text.length > 24000) { mutable.value = mutable.value.copy(message = "SQL 与表结构超过发送上限，请缩短 SQL 或关闭结构附加"); return }
        request { profile, transport ->
            val result = transport.ask(profile.model, text).trim()
            val sql = AiDraft.extract(result) ?: result
            if (sql.length !in 1..16000) throw AiFailure("AI 建议超过编辑器上限或为空，未截断载入")
            return@request { mutable.value = mutable.value.copy(inlineSql = sql, inlineBinding = binding, message = "AI 建议已就绪，未执行") }
        }
    }
    fun clearCompletion() { cancel(); mutable.value = mutable.value.copy(inlineSql = "", inlineBinding = "") }
    fun remove(profile: AiProfile) {
        if (!state.value.ready || state.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val next = state.value.preferences.copy(activeId = "", profiles = state.value.preferences.profiles.filterNot { it.id == profile.id })
                withContext(Dispatchers.IO) { store.save(next); vault.delete(profile.id) }
                mutable.value = mutable.value.copy(preferences = next, models = emptyList(), answer = "", message = "服务已删除")
            } catch (_: Exception) { mutable.value = mutable.value.copy(message = "删除服务失败，请重新打开配置检查") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private fun ask(profile: AiProfile, scope: String, text: String) {
        if (profile != state.value.preferences.active || !state.value.preferences.enabled) return
        request { _, transport ->
            val answer = transport.ask(profile.model, text);
            { mutable.value = mutable.value.copy(answer = answer, answerScope = scope, message = "回答已生成，未执行任何数据库操作") }
        }
    }
    private fun request(operation: (AiProfile, AiClient) -> (() -> Unit)) {
        val initial = state.value
        val profile = initial.preferences.active ?: return
        if (!initial.ready || initial.busy || !initial.preferences.enabled) return
        cancellation = AtomicBoolean(false); val cancelled = cancellation
        mutable.value = initial.copy(busy = true, answer = "", inlineSql = "", inlineBinding = "", message = "正在请求 ${profile.name}…")
        viewModelScope.launch {
            try {
                val complete = withContext(Dispatchers.IO) {
                    val key = vault.loadBound(profile.id, profile.binding) ?: charArrayOf()
                    try {
                        val transport = createClient(profile.service, key)
                        synchronized(clientLock) { if (cancelled.get()) { transport.close(); throw AiFailure("AI 请求已取消") }; client = transport }
                        transport.use { operation(profile, it) }
                    } finally { key.fill('\u0000'); synchronized(clientLock) { client = null } }
                }
                if (!cancelled.get()) complete()
            } catch (error: Exception) { if (!cancelled.get()) mutable.value = mutable.value.copy(message = (error as? AiFailure)?.safeMessage ?: "AI 请求或响应格式无效；请检查配置，未自动重试") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun cancel() {
        cancellation.set(true)
        synchronized(clientLock) { client?.close() }
        if (state.value.busy) mutable.value = mutable.value.copy(message = "正在取消 AI 请求…")
    }
    fun clear() { cancel(); mutable.value = mutable.value.copy(answer = "", answerScope = "", message = "本次问答已清除") }
    override fun onCleared() { cancel() }
}
