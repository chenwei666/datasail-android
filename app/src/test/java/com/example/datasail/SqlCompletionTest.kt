package com.example.datasail

import com.example.datasail.core.DatabaseKind
import com.example.datasail.core.DbValue
import org.junit.Assert.*
import org.junit.Test

class SqlCompletionTest {
    private fun candidates(sql: String, cursor: Int = sql.length, kind: DatabaseKind = DatabaseKind.SQL_SERVER) =
        SqlCompletion.suggestions(sql, cursor, kind, listOf("orders", "order]detail"), listOf("order_id", "amount"))
    @Test fun keywordAndMiddleTokenReplacementPreserveSuffixAndCaret() {
        val source = "SELCT amount FROM orders"
        val item = candidates(source, 3).single { it.label == "SELECT" }
        val (result, caret) = SqlCompletion.apply(source, item)
        assertEquals("SELECT amount FROM orders", result); assertEquals(6, caret)
    }
    @Test fun stringsCommentsAndQuotedNamesAreNeverRewritten() {
        for (sql in listOf("SELECT 'SEL", "SELECT 'x''SEL", "-- SEL", "/* a /* b */ SEL", "SELECT [ord", "SELECT \"ord", "SELECT `ord", "SELECT \$tag\$ SEL", "SELECT 'abc\\' SEL")) assertTrue(sql, candidates(sql).isEmpty())
        assertTrue(candidates("-- comment\nSEL").any { it.label == "SELECT" })
        assertTrue(candidates("/* comment */ SEL").any { it.label == "SELECT" })
        assertTrue(candidates("SELECT 123").isEmpty())
    }
    @Test fun metadataIdentifiersUseDialectQuoting() {
        assertEquals("[order]]detail]", candidates("ord").single { it.label == "order]detail" }.insert)
        assertEquals("`orders`", candidates("ord", kind = DatabaseKind.MYSQL).single { it.label == "orders" }.insert)
        assertEquals("\"orders\"", candidates("ord", kind = DatabaseKind.POSTGRESQL).single { it.label == "orders" }.insert)
        assertFalse(candidates("t.or").any { it.label == "ORDER BY" })
    }
    @Test fun clauseAndWhitespaceOfferContextWithoutDeletingClause() {
        for (sql in listOf("SELECT * FROM orders WHERE", "SELECT * FROM orders WHERE ")) {
            val item = candidates(sql).first { it.label == "order_id" }
            val result = SqlCompletion.apply(sql, item).first
            assertEquals("SELECT * FROM orders WHERE [order_id]", result)
        }
        assertEquals(listOf("orders", "order]detail"), candidates("SELECT * FROM ").map { it.label })
        assertEquals(listOf("order_id", "amount"), candidates("SELECT * FROM orders WHERE t.").map { it.label })
        assertEquals(emptyList<SqlSuggestion>(), candidates("SELECT * FROM orders -- WHERE "))
    }
    @Test fun gridKeepsTextLeadingZeroAndColumnAddresses() {
        assertEquals("0007", spreadsheetText(DbValue.Text("0007")))
        assertEquals("", spreadsheetText(DbValue.Text("")))
        assertEquals("NULL", spreadsheetText(DbValue.Null))
        assertEquals("A", spreadsheetColumn(0)); assertEquals("Z", spreadsheetColumn(25)); assertEquals("AA", spreadsheetColumn(26)); assertEquals("BL", spreadsheetColumn(63))
    }
}
