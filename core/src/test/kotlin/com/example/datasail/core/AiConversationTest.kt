package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test

class AiConversationTest {
    @Test fun protocolsPreserveRoleSeparationAndEscapeText() {
        for (protocol in AiProtocol.entries) {
            val body = SearchJson.parse(AiWire.conversation(protocol, "test-model", listOf(AiChatTurn("你好\"", "回答")), "继续").body!!).obj()
            val messages = (body[if (protocol == AiProtocol.GEMINI) "contents" else "messages"] as SearchJson.Arr).values.map { it.obj() }
            val turns = if (protocol == AiProtocol.OPENAI) messages.drop(1) else messages
            assertEquals(listOf("user", if (protocol == AiProtocol.GEMINI) "model" else "assistant", "user"), turns.map { it["role"]!!.string() })
            assertEquals(3, turns.size)
            assertFalse(body.containsKey("tools"))
            val first = if (protocol == AiProtocol.GEMINI) ((turns[0]["parts"] as SearchJson.Arr).values[0].obj()["text"]!!.string()) else turns[0]["content"]!!.string()
            assertEquals("你好\"", first)
        }
    }
    @Test fun historyIsBoundedByWholeRecentPairs() {
        val turns = (1..12).map { AiChatTurn("q$it", "a$it") }
        assertEquals(turns.takeLast(8), AiConversation.context(turns, "next"))
        assertEquals(emptyList<AiChatTurn>(), AiConversation.context(listOf(AiChatTurn("q", "a".repeat(24000))), "next"))
        assertEquals(turns.takeLast(1), AiConversation.context(listOf(AiChatTurn("q", "a".repeat(24000))) + turns.takeLast(1), "next"))
        assertThrows(IllegalArgumentException::class.java) { AiConversation.context(turns, " ") }
        assertThrows(IllegalArgumentException::class.java) { AiConversation.context(turns, "a".repeat(24001)) }
    }
}
