package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.sql.Types

class TableActionsTest {
    private val table = TableRef("db", "dbo", "records", 1, false)
    private val schema = TableSchema(table, listOf(
        ColumnDef("id", Types.BIGINT, "bigint", false, 19, 0, true, false, 1),
        ColumnDef("name]x", Types.NVARCHAR, "nvarchar", true, 120, 0, false, false),
        ColumnDef("amount", Types.DECIMAL, "decimal", true, 24, 8, false, false)), false)
    @Test fun unsignedIntegerFiltersPreserveFullRangeAndRejectOverflow() {
        val unsigned = schema.copy(columns = listOf(schema.columns.first().copy(typeName = "BIGINT UNSIGNED", size = 20)))
        val maximum = "18446744073709551615"
        assertEquals(java.math.BigDecimal(maximum), TableViews.compile(unsigned, TableView(listOf(ColumnFilter("id", FilterOperator.EQ, maximum))), SqlNames::quote).parameters.single())
        for (bad in listOf("-1", "18446744073709551616", "1.5")) assertThrows(WorkspaceRejected::class.java) {
            TableViews.compile(unsigned, TableView(listOf(ColumnFilter("id", FilterOperator.EQ, bad))), SqlNames::quote)
        }
        assertThrows(WorkspaceRejected::class.java) { TableViews.compile(schema, TableView(listOf(ColumnFilter("id", FilterOperator.EQ, maximum))), SqlNames::quote) }
    }
    @Test fun unsupportedSqlServerSortAndComparisonAreRejectedLocally() {
        val text = schema.copy(columns = listOf(ColumnDef("body", Types.LONGVARCHAR, "text", true, 5000, 0, false, false)))
        assertThrows(WorkspaceRejected::class.java) { TableViews.compile(text, TableView(sorts = listOf(ColumnSort("body"))), SqlNames::quote, sqlServer = true) }
        assertThrows(WorkspaceRejected::class.java) { TableViews.compile(text, TableView(listOf(ColumnFilter("body", FilterOperator.EQ, "x"))), SqlNames::quote, sqlServer = true) }
        assertTrue(TableViews.compile(text, TableView(listOf(ColumnFilter("body", FilterOperator.CONTAINS, "x"))), SqlNames::quote, sqlServer = true).where.contains("LIKE"))
    }
    @Test fun editingOneRangeConditionReplacesItEvenWhenOperatorChanges() {
        val lower = ColumnFilter("id", FilterOperator.GT, "10")
        val upper = ColumnFilter("id", FilterOperator.LE, "20")
        val next = ColumnFilter("id", FilterOperator.GE, "12")
        assertEquals(listOf(upper, next), TableViews.replaceFilter(TableView(listOf(lower, upper)), lower, next).filters)
    }
    @Test fun literalFiltersStayBoundAndSortingHasStableKeyTieBreaker() {
        val literal = "x' OR 1=1 -- !%_["
        val compiled = TableViews.compile(schema, TableView(listOf(ColumnFilter("name]x", FilterOperator.CONTAINS, literal)), listOf(ColumnSort("amount", true))), SqlNames::quote)
        assertEquals(" WHERE [name]]x] LIKE ? ESCAPE '!'", compiled.where)
        assertEquals(" ORDER BY [amount] DESC,[id] ASC", compiled.order)
        assertEquals(listOf("%x' OR 1=1 -- !!!%!_![%"), compiled.parameters)
        assertFalse(compiled.where.contains(literal))
    }
    @Test fun identityFiltersAndExactDecimalsDoNotUseEditabilityOrDouble() {
        val compiled = TableViews.compile(schema, TableView(listOf(ColumnFilter("id", FilterOperator.EQ, "9007199254740993"), ColumnFilter("amount", FilterOperator.GE, "1234567890123456.12345678"))), SqlNames::quote)
        assertEquals(9007199254740993L, compiled.parameters[0])
        assertEquals("1234567890123456.12345678", compiled.parameters[1].toString())
    }
    @Test fun nullsAndEmptyStringsAreDifferentFilters() {
        val c = TableViews.compile(schema, TableView(listOf(ColumnFilter("name]x", FilterOperator.IS_NULL), ColumnFilter("name]x", FilterOperator.EQ, ""))), SqlNames::quote)
        assertEquals(" WHERE [name]]x] IS NULL AND [name]]x] = ?", c.where); assertEquals(listOf(""), c.parameters)
    }
    @Test fun invalidIdentifiersTypesAndBoundsAreRejected() {
        listOf(TableView(listOf(ColumnFilter("id];DROP TABLE records--", FilterOperator.EQ, "1"))),
            TableView(listOf(ColumnFilter("id", FilterOperator.EQ, "1 OR 1=1"))),
            TableView(listOf(ColumnFilter("id", FilterOperator.CONTAINS, "1"))),
            TableView(List(9) { ColumnFilter("id", FilterOperator.EQ, "1") }),
            TableView(sorts = listOf(ColumnSort("id"), ColumnSort("id", true)))).forEach { view ->
            assertThrows(WorkspaceRejected::class.java) { TableViews.compile(schema, view, SqlNames::quote) }
        }
    }
    @Test fun invalidDatesDoNotNormalizeIntoADifferentFilter() {
        val dates = schema.copy(columns = schema.columns + ColumnDef("day", Types.DATE, "date", true, 10, 0, false, false) + ColumnDef("instant", -155, "datetimeoffset", true, 34, 7, false, false))
        for ((column, value) in listOf("day" to "2023-02-29", "instant" to "not-a-date")) {
            assertThrows(WorkspaceRejected::class.java) { TableViews.compile(dates, TableView(listOf(ColumnFilter(column, FilterOperator.EQ, value))), SqlNames::quote) }
        }
        val valid = TableViews.compile(dates, TableView(listOf(ColumnFilter("day", FilterOperator.EQ, "2024-02-29"))), SqlNames::quote)
        assertEquals("2024-02-29", valid.parameters.single().toString())
    }
    @Test fun sqlServerTimeUsesExactTypedTextInsteadOfDriverDatetimeCoercion() {
        val times = schema.copy(columns = listOf(ColumnDef("clock", Types.TIME, "time", true, 16, 7, false, false)))
        val compiled = TableViews.compile(times, TableView(listOf(ColumnFilter("clock", FilterOperator.GT, "12:34:56.1234567"))), SqlNames::quote, sqlServer = true)
        assertEquals(" WHERE [clock] > CONVERT(time(7),?,126)", compiled.where)
        assertEquals(listOf("12:34:56.1234567"), compiled.parameters)
    }
    @Test fun primaryKeyIsNotAppendedTwice() {
        assertEquals(" ORDER BY [id] DESC", TableViews.compile(schema, TableView(sorts = listOf(ColumnSort("id", true))), SqlNames::quote).order)
    }
    private fun plan(action: String = "UPDATE", filters: String = "[{\"column\":\"id\",\"operator\":\"EQ\",\"value\":\"1\"}]", values: String = "{\"name]x\":\"00123\"}") = "{\"action\":\"$action\",\"filters\":$filters,\"sorts\":[],\"values\":$values}"
    @Test fun plansPreserveTextAndNullAndNeverAcceptSqlOrTargetFields() {
        val parsed = AiTableAction.parse(plan(values = "{\"name]x\":null}")); assertTrue(parsed.values.getValue("name]x").isNull)
        assertEquals("00123", AiTableAction.parse(plan()).values.getValue("name]x").text)
        listOf(plan().dropLast(1) + ",\"sql\":\"DELETE FROM records\"}", plan().dropLast(1) + ",\"table\":\"other\"}", plan(values = "{\"id\":1}"), plan().dropLast(1) + ",\"action\":\"DELETE\"}").forEach { invalid ->
            assertThrows(WorkspaceRejected::class.java) { AiTableAction.parse(invalid) }
        }
    }
    @Test fun broadAndContradictoryMutationsAreRejected() {
        listOf(plan(filters = "[]"), plan("DELETE", values = "{\"id\":\"1\"}"), plan("INSERT"), plan("READ"), plan("EXEC"), "{\"error\":\"need detail\"}").forEach {
            assertThrows(WorkspaceRejected::class.java) { AiTableAction.parse(it) }
        }
    }
    @Test fun writeAuthorizationIsCheckedBeforeAnyDatabaseAccess() {
        val session = object : SqlWorkspaceSession {
            override val id = "test"
            override val profile get() = throw AssertionError("must not access session")
            override val catalog = "db"
            override val writable = true
            override fun describe(table: TableRef): TableSchema = throw AssertionError("must not read metadata")
            override fun page(table: TableRef, offset: Int, columnOffset: Int, view: TableView): ResultPage = throw AssertionError("must not read rows")
            override fun catalogs() = emptyList<String>()
            override fun schemas() = emptyList<String>()
            override fun tables(schema: String) = emptyList<TableRef>()
            override fun switchCatalog(name: String) {}
            override fun select(sql: String): ResultPage = throw AssertionError()
            override fun isClosed() = false
            override fun close() {}
        }
        assertThrows(WorkspaceRejected::class.java) { AiTableActions.prepare(session, table, AiTableAction.parse(plan()), false) }
    }
    @Test fun answersRemainSeparateFromExplicitWholeTableDeletion() {
        assertEquals(TableAction.ANSWER, AiTableAction.parse("{\"answer\":\"这是考勤记录表，但业务含义需确认\"}").action)
        assertEquals(TableAction.DROP_TABLE, AiTableAction.parse(plan("DROP_TABLE", filters = "[]", values = "{}")).action)
        assertThrows(WorkspaceRejected::class.java) { AiTableAction.parse(plan("DROP_TABLE", values = "{}")) }
        assertThrows(WorkspaceRejected::class.java) { AiTableAction.parse("{\"answer\":\"hello\",\"sql\":\"DROP TABLE x\"}") }
    }
    @Test fun eachProtocolKeepsDraftAndActionSystemRulesSeparate() {
        AiProtocol.entries.forEach { protocol ->
            val draft = AiWire.chat(protocol, "model", "hello").body!!
            val action = AiWire.chat(protocol, "model", "hello", tableAction = true).body!!
            assertTrue(draft.contains("Explain or draft SQL only")); assertFalse(action.contains("Explain or draft SQL only"))
            assertTrue(action.contains("Never execute or claim success"))
        }
    }
}
