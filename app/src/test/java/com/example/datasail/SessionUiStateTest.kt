package com.example.datasail

import com.example.datasail.core.*
import org.junit.Assert.*
import org.junit.Test

class SessionUiStateTest {
    private val oldTable = TableRef("old_db", "dbo", "records", 1, false)
    private val old = WorkspaceUiState(connected = true, catalog = "old_db", schema = "dbo", writable = true,
        schemas = listOf("dbo"), tables = listOf(oldTable), result = ResultPage(emptyList(), emptyList(), false))
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
