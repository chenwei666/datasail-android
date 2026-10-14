package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.sql.Types

class FloatingEditTest {
    private fun column(single: Boolean) = ColumnDef("coordinate", if (single) Types.REAL else Types.DOUBLE,
        if (single) "real" else "float", true, if (single) 4 else 8, 0, false, false)
    @Test fun floatingInputsRoundTripWithoutSilentlyRounding() {
        assertEquals(DbValue.Approximate("1.0000000000000002"), Values.parse(column(false), CellInput("1.0000000000000002")))
        assertEquals(DbValue.Approximate("1.0000001"), Values.parse(column(true), CellInput("1.0000001")))
        assertEquals(DbValue.Null, Values.parse(column(true), CellInput(isNull = true)))
        for (single in listOf(true, false)) for (value in listOf("NaN", "Infinity", "-Infinity", "1e400", "1e-400", "1e-310", "0.100000000000000000001"))
            assertThrows(WorkspaceRejected::class.java) { Values.parse(column(single), CellInput(value)) }
        assertThrows(WorkspaceRejected::class.java) { Values.parse(column(true), CellInput("1e-40")) }
        assertThrows(WorkspaceRejected::class.java) { Values.parse(column(true), CellInput("1.0000000000000002")) }
    }
    @Test fun tablesWithCoordinatesNeedNoRowVersionButRetainOtherGuards() {
        val key = ColumnDef("id", Types.INTEGER, "int", false, 4, 0, false, false, 1)
        val schema = TableSchema(TableRef("db", "dbo", "records", 1, false), listOf(key, column(false), column(true).copy(name = "other")), false)
        assertNull(schema.readOnlyReason)
        assertNotNull(schema.copy(hasTriggers = true).readOnlyReason)
        assertNotNull(schema.copy(columns = schema.columns.drop(1)).readOnlyReason)
        assertTrue(schema.copy(columns = schema.columns + key.copy(name = "payload", jdbcType = Types.OTHER, typeName = "geography", primaryKeyOrder = 0)).readOnlyReason!!.contains("payload (geography)"))
    }
}
