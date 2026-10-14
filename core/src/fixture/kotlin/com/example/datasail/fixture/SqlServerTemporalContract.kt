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
        check(page.tableSchema?.readOnlyReason != null) // Temporal writes have not been enabled by a read fix.
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
