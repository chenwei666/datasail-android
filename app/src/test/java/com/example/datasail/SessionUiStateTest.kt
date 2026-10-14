package com.example.datasail

import com.example.datasail.core.*
import org.junit.Assert.*
import org.junit.Test

class SessionUiStateTest {
    private val oldTable = TableRef("old_db", "dbo", "records", 1, false)
    private val old = WorkspaceUiState(connected = true, catalog = "old_db", schema = "dbo", writable = true,
        schemas = listOf("dbo"), tables = listOf(oldTable), result = ResultPage(emptyList(), emptyList(), false))
    @Test fun validationFailurePreservesRowsButBlocksOldEditingContext() {
        val updated = old.afterFailure(WorkspaceRejected("invalid input"))
        assertSame(old.result, updated.result); assertTrue(updated.resultStale)
        assertTrue(updated.connected); assertNull(updated.preview)
    }
    @Test fun lostConnectionClearsRowsAndEditingPrivileges() {
        val updated = old.afterFailure(java.net.SocketTimeoutException())
        assertNull(updated.result); assertFalse(updated.connected); assertFalse(updated.writable)
    }
    @Test fun rememberedViewsAndDraftsAreBoundedAndIsolatedByConnection() {
        val memory = WorkspaceMemory()
        memory.draft("a", "SELECT '0007'"); memory.draft("b", "SELECT 2")
        val schema = TableSchema(oldTable, emptyList(), false)
        val page = old.result!!.copy(tableSchema = schema, view = TableView(listOf(ColumnFilter("id", FilterOperator.GT, "5"))))
        memory.remember("a", page)
        assertNull(memory.last("b")); assertEquals(page.view, memory.last("a")!!.view)
        assertEquals("SELECT '0007'", memory.draft("a"))
        memory.forgetView("a", oldTable)
        assertNull(memory.location("a", oldTable)); assertNull(memory.last("a"))
        assertEquals("SELECT '0007'", memory.draft("a"))
        repeat(40) { memory.record("a", QueryRun("SELECT $it", "db", "dbo", 1, 10)) }
        assertEquals(30, memory.history("a").size); assertEquals("SELECT 39", memory.history("a").first().sql)
        memory.clear(); assertNull(memory.last("a")); assertTrue(memory.history("a").isEmpty())
    }
    @Test fun partialSwitchFailureCannotDisplayPreviousDatabaseOrObjects() {
        val updated = old.withSession(SessionSnapshot("new_db", false, true))
        assertEquals("new_db", updated.catalog)
        assertEquals("—", updated.schema)
        assertFalse(updated.writable)
        assertTrue(updated.tables.isEmpty()); assertTrue(updated.schemas.isEmpty()); assertNull(updated.result)
    }
    @Test fun successfulCommitFollowedByCloseCannotLeaveWritableUi() {
        val updated = old.withSession(SessionSnapshot(null, false, false))
        assertFalse(updated.connected); assertFalse(updated.writable); assertNull(updated.result)
        assertEquals("—", updated.catalog)
    }
    @Test fun recoverableErrorInSameDatabasePreservesTarget() {
        val updated = old.withSession(SessionSnapshot("old_db", true, true))
        assertEquals("old_db", updated.catalog); assertEquals(listOf(oldTable), updated.tables)
    }
}
