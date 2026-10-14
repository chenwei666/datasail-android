package com.example.datasail.core

import java.sql.Types
import org.junit.Assert.*
import org.junit.Test

class LargeTextEditTest {
    private val key = ColumnDef("id", Types.INTEGER, "int", false, 10, 0, false, false, 1)
    private val text = ColumnDef("gps_location", Types.NVARCHAR, "nvarchar", true, Int.MAX_VALUE, 0, false, false)

    @Test fun declaredCapacityDoesNotBlockLoadedRowsButUnsupportedTypesStillDo() {
        val schema = TableSchema(TableRef("db", "dbo", "records", 1, false), listOf(key, text), false)
        assertNull(schema.readOnlyReason)
        assertNotNull(schema.copy(hasTriggers = true).readOnlyReason)
        assertNotNull(schema.copy(columns = listOf(text)).readOnlyReason)
        assertNotNull(schema.copy(columns = listOf(key, text.copy(jdbcType = Types.LONGNVARCHAR, typeName = "ntext"))).readOnlyReason)
        assertEquals(DbValue.Text("x".repeat(4000)), Values.parse(text, CellInput("x".repeat(4000))))
        assertThrows(WorkspaceRejected::class.java) { Values.parse(text, CellInput("x".repeat(4001))) }
    }

    @Test fun comparisonsUseWhitelistedTypesAndValidSqlServerLengths() {
        assertEquals("nvarchar(max)", SqlServerText.sqlType(text))
        assertEquals("varchar(max)", SqlServerText.sqlType(text.copy(jdbcType = Types.VARCHAR)))
        assertEquals("varchar(8000)", SqlServerText.sqlType(text.copy(jdbcType = Types.VARCHAR, size = 8000)))
        assertEquals("nchar(100)", SqlServerText.sqlType(text.copy(jdbcType = Types.NCHAR, size = 100)))
        assertEquals("nvarchar(30)", SqlServerText.sqlType(text.copy(typeName = "untrusted);", size = 30)))
        for (invalid in listOf(text.copy(size = 4001), text.copy(size = 0), text.copy(jdbcType = Types.NCHAR), text.copy(jdbcType = Types.OTHER))) {
            assertThrows(WorkspaceRejected::class.java) { SqlServerText.sqlType(invalid) }
        }
    }
}
