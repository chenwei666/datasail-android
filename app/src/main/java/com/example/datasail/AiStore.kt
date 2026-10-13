package com.example.datasail

import android.content.Context
import android.util.AtomicFile
import com.example.datasail.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class AiProfile(val id: String = UUID.randomUUID().toString(), val name: String = "自定义服务",
    val protocol: AiProtocol = AiProtocol.OPENAI, val endpoint: String = AiProtocol.OPENAI.defaultEndpoint, val model: String = "") {
    val service get() = AiService(protocol, endpoint)
    val binding get() = (id + "\n" + service.binding).toByteArray(Charsets.UTF_8)
}
internal data class AiPreferences(val enabled: Boolean = false, val activeId: String = "", val profiles: List<AiProfile> = emptyList()) {
    val active get() = profiles.find { it.id == activeId }
}
internal class AiStore(context: Context) {
    companion object { private val lock = Any() }
    private val file = AtomicFile(File(context.filesDir, "ai-services-v1.json"))
    fun load(): AiPreferences = synchronized(lock) { loadLocked() }
    private fun loadLocked(): AiPreferences {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return AiPreferences()
        val root = file.openRead().use { require(it.channel.size() <= 131072); JSONObject(it.bufferedReader().readText()) }
        require(root.getInt("schema") == 1)
        val rows = root.getJSONArray("profiles"); require(rows.length() <= 24)
        val profiles = (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            AiProfile(it.getString("id"), it.getString("name"), AiProtocol.valueOf(it.getString("protocol")), it.getString("endpoint"), it.getString("model"))
        } }
        validate(profiles)
        return AiPreferences(root.getBoolean("enabled"), root.getString("activeId"), profiles)
    }
    private fun validate(profiles: List<AiProfile>) {
        require(profiles.size <= 24 && profiles.distinctBy { it.id }.size == profiles.size)
        profiles.forEach { require(it.id.length in 1..80 && it.name.isNotBlank() && it.name.length <= 80 && it.endpoint.length <= 2048 && it.model.length <= 256); it.service.base }
    }
    fun save(preferences: AiPreferences) = synchronized(lock) { saveLocked(preferences) }
    private fun saveLocked(preferences: AiPreferences) {
        validate(preferences.profiles)
        val rows = JSONArray()
        preferences.profiles.forEach { rows.put(JSONObject().put("id", it.id).put("name", it.name).put("protocol", it.protocol.name).put("endpoint", it.service.base).put("model", it.model)) }
        val bytes = JSONObject().put("schema", 1).put("enabled", preferences.enabled).put("activeId", preferences.activeId).put("profiles", rows).toString().toByteArray()
        require(bytes.size <= 131072)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }
}
internal data class AiPreset(val name: String, val endpoint: String, val protocol: AiProtocol = AiProtocol.OPENAI)
internal val aiPresets = listOf(
    AiPreset("OpenAI", "https://api.openai.com/v1"),
    AiPreset("Claude", "https://api.anthropic.com/v1", AiProtocol.ANTHROPIC),
    AiPreset("Gemini", "https://generativelanguage.googleapis.com/v1beta", AiProtocol.GEMINI),
    AiPreset("DeepSeek", "https://api.deepseek.com/v1"),
    AiPreset("通义千问 · 阿里云", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    AiPreset("豆包 · 火山方舟", "https://ark.cn-beijing.volces.com/api/v3"),
    AiPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"),
    AiPreset("Kimi · Moonshot", "https://api.moonshot.cn/v1"),
    AiPreset("MiniMax", "https://api.minimax.io/v1"),
    AiPreset("硅基流动", "https://api.siliconflow.cn/v1"),
    AiPreset("百度千帆", "https://qianfan.baidubce.com/v2"),
    AiPreset("腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1"),
    AiPreset("Grok · xAI", "https://api.x.ai/v1"),
    AiPreset("OpenRouter", "https://openrouter.ai/api/v1"),
    AiPreset("Groq", "https://api.groq.com/openai/v1"),
    AiPreset("Mistral", "https://api.mistral.ai/v1"),
    AiPreset("Together", "https://api.together.xyz/v1"),
    AiPreset("NVIDIA NIM", "https://integrate.api.nvidia.com/v1"),
    AiPreset("Perplexity", "https://api.perplexity.ai"),
    AiPreset("自定义兼容服务", "")
)
