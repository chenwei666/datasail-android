package com.example.datasail.fixture

import com.example.datasail.core.*

/** Real server contract shared by the JVM and Android; synthetic literals only. */
object SqlServerTemporalContract {
    fun createTable(connection: java.sql.Connection) = connection.createStatement().use { statement ->
        statement.execute("DROP TABLE IF EXISTS dbo.ui_temporal_v091")
        statement.execute("CREATE TABLE dbo.ui_temporal_v091(id int PRIMARY KEY, day_value date, clock_value time(7), legacy_value datetime, minute_value smalldatetime, precise_value datetime2(7), offset_value datetimeoffset(7))")
        statement.execute("INSERT dbo.ui_temporal_v091 VALUES(1,'2024-02-29','23:59:59.1234567','2024-02-29T12:34:56.123','2024-02-29T12:34:00','2024-02-29T12:34:56.1234567','2024-02-29T12:34:56.1234567+07:00'),(2,NULL,NULL,NULL,NULL,NULL,NULL)")
    }

    fun verifyPage(page: ResultPage) {
        check(page.rows.size == 2 && page.columns.size == 7)
        check(page.rows[0].drop(1) == listOf("2024-02-29", "23:59:59.1234567", "2024-02-29 12:34:56.123",
            "2024-02-29 12:34:00.0", "2024-02-29 12:34:56.1234567", "2024-02-29 12:34:56.1234567 +07:00").map(DbValue::Temporal))
        check(page.rows[1].drop(1).all { it == DbValue.Null })
        check(page.tableSchema != null && page.tableSchema?.readOnlyReason == null) // V0.10 enables exact temporal binding and conflict predicates.
    }

    fun verifyEditing(session: SqlServerSession, concurrent: (String) -> Unit) {
        val table = session.tables("dbo").single { it.name == "ui_temporal_v091" }
        val initial = session.page(table)
        check(!session.writable)
        check(runCatching { session.preview(MutationKind.UPDATE, 0, mapOf("day_value" to CellInput("2025-01-01"))) }.isFailure)
        session.setWritable(true)
        val unchanged = initial.columns.drop(1).mapIndexed { index, col -> col.name to CellInput((initial.rows[0][index + 1] as DbValue.Temporal).value) }.toMap()
        val noChange = runCatching { session.preview(MutationKind.UPDATE, 0, unchanged) }.exceptionOrNull()
        check(noChange is WorkspaceRejected && noChange.explanation == "没有可提交的变更")
        val inputs = mapOf("day_value" to CellInput("2025-01-01"), "clock_value" to CellInput("01:02:03.1234567"),
            "legacy_value" to CellInput("2025-01-01T01:02:03.127"), "minute_value" to CellInput("2025-01-01T01:02:00"),
            "precise_value" to CellInput("2025-01-01T01:02:03.7654321"), "offset_value" to CellInput("2025-01-01T01:02:03.1234567-03:30"))
        val preview = session.preview(MutationKind.UPDATE, 0, inputs)
        check("2025-01-01" !in preview.sql)
        check(session.confirm(preview.token).status == MutationStatus.COMMITTED)
        val updated = session.page(table)
        check(updated.rows[0].drop(1) == listOf("2025-01-01", "01:02:03.1234567", "2025-01-01 01:02:03.127",
            "2025-01-01 01:02:00.0", "2025-01-01 01:02:03.7654321", "2025-01-01 01:02:03.1234567 -03:30").map(DbValue::Temporal))
        val stale = session.preview(MutationKind.UPDATE, 0, mapOf("day_value" to CellInput("2026-01-01")))
        // Same UTC instant, different local offset is still a concurrent change.
        concurrent("UPDATE dbo.ui_temporal_v091 SET offset_value=SWITCHOFFSET(offset_value,'+07:00') WHERE id=1")
        check(session.confirm(stale.token).status == MutationStatus.CONFLICT)
        check(session.page(table).rows[0][1] == DbValue.Temporal("2025-01-01"))
        val clear = session.preview(MutationKind.UPDATE, 0, inputs.keys.associateWith { CellInput(isNull = true) })
        check(session.confirm(clear.token).status == MutationStatus.COMMITTED)
        check(session.page(table).rows[0].drop(1).all { it == DbValue.Null })
        // Restore fixture for callers that subsequently verify the original page.
        val restore = session.preview(MutationKind.UPDATE, 0, initial.columns.drop(1).mapIndexed { index, col ->
            col.name to CellInput((initial.rows[0][index + 1] as DbValue.Temporal).value)
        }.toMap())
        check(session.confirm(restore.token).status == MutationStatus.COMMITTED)
        session.setWritable(false)
        verifyPage(session.page(table))
    }

    fun verify(session: SqlServerSession) {
        val expressions = linkedMapOf(
            "date" to "CAST('2024-02-29' AS date)",
            "time" to "CAST('23:59:59.1234567' AS time(7))",
            "datetime" to "CAST('2024-02-29T12:34:56.123' AS datetime)",
            "smalldatetime" to "CAST('2024-02-29T12:34:00' AS smalldatetime)",
            "datetime2" to "CAST('2024-02-29T12:34:56.1234567' AS datetime2(7))",
            "datetimeoffset" to "CAST('2024-02-29T12:34:56.1234567+07:00' AS datetimeoffset(7))"
        )
        expressions.forEach { (type, expression) ->
            val value = session.select("SELECT $expression AS sample").rows.single().single()
            check(value is DbValue.Temporal) { "$type should be temporal: $value" }
            val expected = when (type) {
                "date" -> "2024-02-29"
                "time" -> "23:59:59.1234567"
                "datetime" -> "2024-02-29 12:34:56.123"
                "smalldatetime" -> "2024-02-29 12:34:00.0"
                "datetime2" -> "2024-02-29 12:34:56.1234567"
                else -> "2024-02-29 12:34:56.1234567 +07:00"
            }
            check(value.value == expected) { "$type lost precision: ${value.value}" }
            check(session.select("SELECT CAST(NULL AS $type) AS sample").rows.single().single() == DbValue.Null)
        }
        check(session.select("SELECT 1 AS still_connected").rows.single().single() == DbValue.Integer(1))
    }
}
