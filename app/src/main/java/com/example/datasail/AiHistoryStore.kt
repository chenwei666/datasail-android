package com.example.datasail

import android.content.Context
import com.example.datasail.core.AiChatTurn
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Local encrypted history, excluded from Android backup. No stored execution/approval tokens. */
internal class AiHistoryStore(context: Context) {
    private val vault = PasswordVault(context, "ai-history-v1", 131072)
    fun load(id: String): List<AiChatTurn> {
        val chars = vault.loadBound(id, id.toByteArray()) ?: return emptyList()
        try {
            val array = JSONArray(String(chars)); require(array.length() <= 16)
            return (0 until array.length()).map { array.getJSONObject(it).let { row -> AiChatTurn(row.getString("question"), row.getString("answer")) } }.also { require(it.sumOf { t -> t.question.length + t.answer.length } <= 64000) }
        } finally { chars.fill('\u0000') }
    }
    fun save(id: String, turns: List<AiChatTurn>) {
        val array = JSONArray()
        retain(turns).forEach { array.put(JSONObject().put("question", it.question).put("answer", it.answer)) }
        val chars = array.toString().toCharArray()
        try { vault.saveBound(id, id.toByteArray(), chars); vault.trimTo(50) } finally { chars.fill('\u0000') }
    }
    fun clear(id: String) = vault.delete(id)
    companion object {
        fun retain(turns: List<AiChatTurn>): List<AiChatTurn> {
            var budget = 64000
            return turns.takeLast(16).asReversed().takeWhile { budget -= it.question.length + it.answer.length; budget >= 0 }.asReversed()
        }
    }
}
internal fun aiHistoryKey(profile: AiProfile?, workspace: WorkspaceUiState, mode: String): String {
    val parts = listOf(profile?.id.orEmpty(), profile?.service?.binding.orEmpty(), profile?.model.orEmpty(), mode,
        if (workspace.connected) workspace.connectionKey else "disconnected", workspace.catalog.takeIf { workspace.connected }.orEmpty(),
        workspace.result?.tableSchema?.table?.schema.orEmpty(), workspace.result?.tableSchema?.table?.name.orEmpty())
    return MessageDigest.getInstance("SHA-256").digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray()).joinToString("") { "%02x".format(it) }
}
