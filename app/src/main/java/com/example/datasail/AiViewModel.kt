package com.example.datasail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.datasail.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal data class AiUiState(val ready: Boolean = false, val busy: Boolean = false, val preferences: AiPreferences = AiPreferences(),
    val historyKey: String = "", val historyReady: Boolean = false, val chat: List<AiChatTurn> = emptyList(), val chatProfile: AiProfile? = null, val inlineSql: String = "", val inlineBinding: String = "", val models: List<String> = emptyList(), val modelsBinding: String = "", val answer: String = "", val answerScope: String = "",
    val message: String = "配置服务后即可使用；数据库功能无需启用 AI。")
internal class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val historyStore = AiHistoryStore(application)
    private val historyLock = kotlinx.coroutines.sync.Mutex()
    private var historyEpoch = 0L
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
    fun bindHistory(key: String) {
        if (key == state.value.historyKey && state.value.historyReady) return
        cancel()
        val ticket = ++historyEpoch
        mutable.value = mutable.value.copy(historyKey = key, historyReady = false, chat = emptyList(), answer = "")
        viewModelScope.launch {
            try {
                val turns = historyLock.withLock { withContext(Dispatchers.IO) { historyStore.load(key) } }
                if (ticket == historyEpoch) mutable.value = mutable.value.copy(chat = turns, historyReady = true, chatProfile = state.value.preferences.active)
            } catch (_: Exception) { if (ticket == historyEpoch) mutable.value = mutable.value.copy(historyReady = false, message = "本地对话无法恢复，可清空后重新开始") }
        }
    }
    private fun rememberTurn(key: String, question: String, answer: String) {
        if (key != state.value.historyKey) return
        val turns = AiHistoryStore.retain(state.value.chat + AiChatTurn(question, answer))
        mutable.value = mutable.value.copy(chat = turns, chatProfile = state.value.preferences.active)
        viewModelScope.launch { historyLock.withLock {
            try { withContext(Dispatchers.IO) { historyStore.save(key, turns) } }
            catch (_: Exception) { if (key == state.value.historyKey) mutable.value = mutable.value.copy(message = "回答已显示，但本地对话保存失败") }
        } }
    }
    fun save(profile: AiProfile, key: CharArray, fetchModels: Boolean = false) {
        if (!state.value.ready || state.value.busy) { key.fill('\u0000'); return }
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            var saved = false
            try {
                val next = state.value.preferences.copy(activeId = profile.id, profiles = state.value.preferences.profiles.filterNot { it.id == profile.id } + profile)
                withContext(Dispatchers.IO) {
                    profile.service.base
                    require(key.size <= 4096 && key.all { it.code in 33..126 })
                    // Saving metadata first cannot leak a key: ciphertext remains bound to its exact old endpoint.
                    store.save(next)
                    if (key.isNotEmpty()) vault.saveBound(profile.id, profile.binding, key)
                }
                mutable.value = mutable.value.copy(preferences = next, models = if (state.value.modelsBinding == profile.service.binding) state.value.models else emptyList(), answer = "", message = "服务已保存；空密钥保留同地址原密钥，修改地址后需重新填写")
                saved = true
            } catch (_: Exception) {
                val persisted = withContext(Dispatchers.IO) { runCatching { store.load() }.getOrNull() }
                mutable.value = mutable.value.copy(preferences = persisted ?: mutable.value.preferences, ready = persisted != null, message = "服务或密钥保存失败，请检查 HTTPS 基础地址并重新填写密钥")
            }
            finally { key.fill('\u0000'); mutable.value = mutable.value.copy(busy = false) }
            if (saved && fetchModels) models()
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
    fun models() = request(requireEnabled = false) { profile, transport ->
        val result = transport.models();
        { mutable.value = mutable.value.copy(models = result.names, modelsBinding = profile.service.binding,
            message = "取得 ${result.names.size} 个模型${if (result.limited) "（达到分页上限，可手填其它 ID）" else ""}；是否可调用取决于账号权限") }
    }
    fun chat(workspace: WorkspaceViewModel, text: String) {
        if (!aiAllowed(workspace) || workspace.state.value.busy || text.isBlank() || text.length > 12000) return
        val initial = state.value
        val scope = aiScope(workspace)
        val history = if (initial.chatProfile == initial.preferences.active) initial.chat else emptyList()
        val historyKey = initial.historyKey
        request { profile, transport ->
            val context = AiConversation.context(history, text)
            val answer = transport.converse(profile.model, context, text);
            {
                if (aiAllowed(workspace) && aiScope(workspace) == scope && state.value.preferences.active == profile) {
                    rememberTurn(historyKey, text, answer)
                    mutable.value = mutable.value.copy(message = "回答已收到；未读取或修改数据库。")
                } else mutable.value = mutable.value.copy(message = "连接或权限已变化，已丢弃回答")
            }
        }
    }
    fun clearChat() {
        cancel(); val key = state.value.historyKey
        val ticket = ++historyEpoch
        mutable.value = mutable.value.copy(chat = emptyList(), answer = "", historyReady = false, message = "正在清空本地对话…")
        viewModelScope.launch { historyLock.withLock {
            try {
                withContext(Dispatchers.IO) { historyStore.clear(key) }
                if (ticket == historyEpoch) mutable.value = mutable.value.copy(historyReady = true, message = "对话已清空")
            } catch (_: Exception) {
                if (ticket == historyEpoch) mutable.value = mutable.value.copy(message = "本地对话删除失败，请重试；未继续写入历史")
            }
        } }
    }
    fun askApproved(workspace: WorkspaceViewModel, approval: AiApproval) {
        if (workspace.state.value.busy || !aiAllowed(workspace) || aiScope(workspace) != approval.scope || approval.profile != state.value.preferences.active || !state.value.historyReady) return
        val key = state.value.historyKey
        val history = state.value.chat
        request { _, transport ->
            val answer = transport.contextual(approval.profile.model, history, approval.text);
            {
                if (aiAllowed(workspace) && aiScope(workspace) == approval.scope && key == state.value.historyKey) {
                    rememberTurn(key, approval.question, answer)
                    mutable.value = mutable.value.copy(answer = answer, answerScope = approval.scope, message = "回答已生成；可继续追问，未执行 SQL")
                }
            }
        }
    }
    internal fun actApproved(workspace: WorkspaceViewModel, approval: AiApproval, table: TableRef, allowWrites: Boolean) {
        if (workspace.state.value.busy || !state.value.historyReady || !aiAllowed(workspace) || aiScope(workspace) != approval.scope || approval.profile != state.value.preferences.active) return
        val historyKey = state.value.historyKey
        val history = state.value.chat
        request { profile, transport ->
            val action = AiTableAction.parse(transport.contextual(profile.model, history, approval.text, operation = true));
            {
                if (aiAllowed(workspace) && aiScope(workspace) == approval.scope && approval.profile == state.value.preferences.active && workspace.state.value.connected && !workspace.state.value.busy) {
                    if (action.action == TableAction.ANSWER) {
                        rememberTurn(historyKey, approval.question, action.answer)
                        mutable.value = mutable.value.copy(message = "回答已收到，未执行数据库操作")
                    } else {
                        val description = "${action.action}：筛选 ${action.view.filters}；变更字段 ${action.values.keys}。" + if (action.action == TableAction.READ) "正在本地查询，结果见表格。" else "仅生成预览；尚未确认提交，不代表已经执行。"
                        rememberTurn(historyKey, approval.question, description)
                        workspace.aiTableAction(approval.scope, table, action, allowWrites)
                        mutable.value = mutable.value.copy(message = "正在校验数据库目标；写入及删除整表需确认")
                    }
                } else mutable.value = mutable.value.copy(message = "目标或权限已变化，已丢弃 AI 操作计划")
            }
        }
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
    private fun request(requireEnabled: Boolean = true, operation: (AiProfile, AiClient) -> (() -> Unit)) {
        val initial = state.value
        val profile = initial.preferences.active ?: return
        if (!initial.ready || initial.busy || (requireEnabled && !initial.preferences.enabled)) return
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
            } catch (error: Exception) { if (!cancelled.get()) mutable.value = mutable.value.copy(message = (error as? AiFailure)?.safeMessage ?: (error as? WorkspaceRejected)?.explanation ?: "AI 请求或响应格式无效；请检查配置，未自动重试") }
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
