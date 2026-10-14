package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AiHistoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun historySurvivesReopeningIsEncryptedAndCanBeDeleted() {
        val id = UUID.randomUUID().toString()
        val store = AiHistoryStore(context)
        val marker = "仅本机测试机密-" + id
        val turns = listOf(AiChatTurn(marker, "你好，继续问吧"))
        try {
            store.save(id, turns)
            assertEquals(turns, AiHistoryStore(context).load(id))
            assertFalse(File(context.noBackupFilesDir, "ai-history-v1").listFiles().orEmpty().any { it.readBytes().toString(Charsets.UTF_8).contains(marker) })
            store.clear(id)
            assertTrue(AiHistoryStore(context).load(id).isEmpty())
        } finally { store.clear(id) }
    }
    @Test fun scopesSeparateServiceModelDatabaseTableAndMode() {
        val profile = AiProfile(name = "synthetic", endpoint = "https://example.test/v1", model = "m1")
        val state = WorkspaceUiState(connected = true, connectionKey = "one", catalog = "db")
        val original = aiHistoryKey(profile, state, "问答")
        assertEquals(original, aiHistoryKey(profile, state.copy(message = "刷新完成", busy = true), "问答"))
        assertNotEquals(original, aiHistoryKey(profile.copy(model = "m2"), state, "问答"))
        assertNotEquals(original, aiHistoryKey(profile.copy(endpoint = "https://other.test/v1"), state, "问答"))
        assertNotEquals(original, aiHistoryKey(profile, state.copy(connectionKey = "two"), "问答"))
        assertNotEquals(original, aiHistoryKey(profile, state.copy(catalog = "other"), "问答"))
        assertNotEquals(original, aiHistoryKey(profile, state, "操作当前表"))
        fun atTable(name: String) = state.copy(result = ResultPage(emptyList(), emptyList(), false,
            tableSchema = TableSchema(TableRef("db", "dbo", name, 1, false), emptyList(), false)))
        assertNotEquals(aiHistoryKey(profile, atTable("one"), "问答"), aiHistoryKey(profile, atTable("two"), "问答"))
        fun dotted(schema: String, name: String) = state.copy(result = ResultPage(emptyList(), emptyList(), false,
            tableSchema = TableSchema(TableRef("db", schema, name, 1, false), emptyList(), false)))
        // These share the display label a.b.c but are different SQL objects.
        assertNotEquals(aiHistoryKey(profile, dotted("a.b", "c"), "问答"), aiHistoryKey(profile, dotted("a", "b.c"), "问答"))
        assertNotEquals(original, aiHistoryKey(profile, state.copy(connected = false), "问答"))
    }
    @Test fun historyKeepsCompleteRecentTurnsAndCannotLoadWithDifferentBinding() {
        val id = UUID.randomUUID().toString()
        val turns = (1..30).map { AiChatTurn("问题 $it", "回答 $it") }
        try {
            AiHistoryStore(context).save(id, turns)
            assertEquals(turns.takeLast(16), AiHistoryStore(context).load(id))
            assertNull(PasswordVault(context, "ai-history-v1", 131072).loadBound(id, "wrong context".toByteArray()))
            assertEquals(emptyList<AiChatTurn>(), AiHistoryStore.retain(listOf(AiChatTurn("q", "a".repeat(64001)))))
        } finally { AiHistoryStore(context).clear(id) }
    }
}
