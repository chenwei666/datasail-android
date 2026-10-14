package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test

class TemporalEditTest {
    private fun col(type: String, scale: Int = 7) = ColumnDef("moment", SqlServerSession.jdbcType(type), type, true, 8, scale, false, false)
    @Test fun preservesExactTemporalTextWithoutTimezoneConversion() {
        val values = mapOf("date" to "2024-02-29", "time" to "23:59:59.1234567", "datetime" to "2024-02-29T12:34:56.123", "smalldatetime" to "2024-02-29T12:34:00", "datetime2" to "2024-02-29T12:34:56.1234567", "datetimeoffset" to "2024-02-29T12:34:56.1234567+07:00")
        values.forEach { (type, value) -> assertEquals(DbValue.Temporal(value), Values.parse(col(type), CellInput(value))) }
        assertEquals(DbValue.Null, Values.parse(col("datetime2"), CellInput(isNull = true)))
        assertEquals(DbValue.Temporal("2024-02-29T12:34:56.1234567+07:00"), Values.parse(col("datetimeoffset"), CellInput("2024-02-29 12:34:56.1234567 +07:00")))
    }
    @Test fun invalidDatesPrecisionRoundingAndOffsetAreRejected() {
        listOf(col("date") to "2023-02-29", col("datetime") to "2024-01-01T12:00:00.001", col("datetime") to "1700-01-01T12:00:00", col("smalldatetime") to "2024-01-01T12:00:01", col("datetime2", 3) to "2024-01-01T12:00:00.1234", col("time") to "12:00:00.12345678", col("datetimeoffset") to "2024-01-01T12:00:00+15:00", col("date") to "2024-01-01'); DROP TABLE x;--").forEach { (column, value) ->
            assertThrows(value, WorkspaceRejected::class.java) { Values.parse(column, CellInput(value)) }
        }
    }
}
