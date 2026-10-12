package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.sql.Types
import java.math.BigDecimal

class WorkspaceSafetyTest {
    @Test fun `query gate handles strings comments and escaped identifiers`() {
        listOf("SELECT 1", "/* nested /* SELECT INTO */ fine */ SELECT N'; DROP table', [odd]]name] FROM [x]; -- tail",
            "SELECT 'a''b;@#:', \"DELETE\" FROM [table]").forEach { assertEquals(it.trim(), ReadQueryPolicy.validate(it)) }
    }
    @Test fun `query gate rejects write forms batches and ambiguous syntax`() {
        listOf("SELECT 1; DELETE FROM t", "SELECT 1 DELETE t", "SELECT * INTO t FROM s", "SELECT NEXT VALUE FOR s",
            "SELECT * FROM OPENROWSET('x')", "SELECT @x=1", "SELECT * FROM #temp", "WITH t AS (SELECT 1) SELECT * FROM t",
            "SELECT 1 /*", "SELECT 'unterminated", "EXEC p", "SELECT 1; SELECT 2", "SELECT 1 GO SELECT 2", "SELECT a::b()")
            .forEach { sql -> assertThrows(sql, WorkspaceRejected::class.java) { ReadQueryPolicy.validate(sql) } }
    }
    @Test fun `identifier quoting cannot introduce a statement`() {
        assertEquals("[a]]; DROP TABLE b--]", SqlNames.quote("a]; DROP TABLE b--"))
        assertThrows(IllegalArgumentException::class.java) { SqlNames.quote("") }
        assertThrows(IllegalArgumentException::class.java) { SqlNames.quote("a\u0000") }
    }
    @Test fun `SAN identity does not fall back to CN or match wrong hosts`() {
        assertTrue(SqlServerTrustManager.matchesSan("db.example.com", listOf(listOf(2, "*.example.com"))))
        assertTrue(SqlServerTrustManager.matchesSan("DB.EXAMPLE.COM", listOf(listOf(2, "db.example.com"))))
        assertTrue(SqlServerTrustManager.matchesSan("10.0.2.2", listOf(listOf(7, "10.0.2.2"))))
        assertFalse(SqlServerTrustManager.matchesSan("a.db.example.com", listOf(listOf(2, "*.example.com"))))
        assertFalse(SqlServerTrustManager.matchesSan("example.com", listOf(listOf(2, "*.example.com"))))
        assertFalse(SqlServerTrustManager.matchesSan("db.example.com", listOf(listOf(2, "db.example.com.attacker.test"))))
        assertFalse(SqlServerTrustManager.matchesSan("127.0.0.1", listOf(listOf(2, "127.0.0.1"))))
        assertFalse(SqlServerTrustManager.matchesSan("localhost", null))
    }
    private fun column(type: Int, size: Int = 100, scale: Int = 0, nullable: Boolean = true) =
        ColumnDef("v", type, "synthetic", nullable, size, scale, false, false)
    @Test fun `typed inputs distinguish null empty text leading zeros and exact decimal`() {
        assertNotEquals(Values.display(DbValue.Null), Values.display(DbValue.Text("NULL")))
        assertEquals(DbValue.Text("00123"), Values.parse(column(Types.NVARCHAR), CellInput("00123")))
        assertEquals(DbValue.Text(""), Values.parse(column(Types.NVARCHAR), CellInput("")))
        assertEquals(DbValue.Null, Values.parse(column(Types.NVARCHAR), CellInput(isNull = true)))
        assertEquals(DbValue.Decimal(BigDecimal("1234567890123456.12345678")),
            Values.parse(column(Types.DECIMAL, 24, 8), CellInput("1234567890123456.12345678")))
        assertEquals(DbValue.Integer(Long.MAX_VALUE), Values.parse(column(Types.BIGINT), CellInput(Long.MAX_VALUE.toString())))
    }
    @Test fun `invalid ranges precision scale and nullability reject before SQL`() {
        listOf(column(Types.TINYINT) to CellInput("256"), column(Types.BIGINT) to CellInput("9223372036854775808"),
            column(Types.DECIMAL, 4, 2) to CellInput("123.45"), column(Types.DECIMAL, 4, 2) to CellInput("1.234"),
            column(Types.NVARCHAR, 2) to CellInput("abc"), column(Types.INTEGER, nullable = false) to CellInput(isNull = true),
            column(Types.BINARY) to CellInput("12")).forEach { (col, value) ->
            assertThrows(WorkspaceRejected::class.java) { Values.parse(col, value) }
        }
    }
    @Test fun `views triggers unsupported keys and tables without keys stay read only`() {
        val table = TableRef("db", "dbo", "t", 1, false)
        val key = column(Types.INTEGER).copy(primaryKeyOrder = 1)
        assertNull(TableSchema(table, listOf(key), false).readOnlyReason)
        assertNotNull(TableSchema(table.copy(view = true), listOf(key), false).readOnlyReason)
        assertNotNull(TableSchema(table, listOf(key), true).readOnlyReason)
        assertNotNull(TableSchema(table, listOf(column(Types.INTEGER)), false).readOnlyReason)
        assertNotNull(TableSchema(table, listOf(key.copy(jdbcType = Types.OTHER)), false).readOnlyReason)
    }
}
