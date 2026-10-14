package com.example.datasail.fixture

import com.example.datasail.core.*
import java.sql.Connection

/** A max-length declaration is not a truncated value. No rowversion or production data. */
object SqlServerLargeTextContract {
    fun createTable(connection: Connection) {
        connection.createStatement().use { it.execute("""
            DROP TABLE IF EXISTS dbo.ui_large_text_v0122;
            CREATE TABLE dbo.ui_large_text_v0122(id int PRIMARY KEY, emp_code nvarchar(30) NOT NULL,
                gps_location nvarchar(max) NULL, note varchar(max) NULL, bounded varchar(8000) NULL);
            INSERT dbo.ui_large_text_v0122 VALUES(1,N'0007',NULL,'original','bounded');
        """.trimIndent()) }
    }
    fun verify(session: SqlServerSession, external: (String) -> Unit) {
        val table = session.tables("dbo").single { it.name == "ui_large_text_v0122" }
        var page = session.page(table)
        check(page.tableSchema!!.version == null)
        check(page.tableSchema!!.readOnlyReason == null) { page.tableSchema!!.readOnlyReason!! }
        session.setWritable(true)
        fun updateCode(value: String) = session.preview(MutationKind.UPDATE, 0, mapOf("emp_code" to CellInput(value)))
        check(session.confirm(updateCode("0008").token).status == MutationStatus.COMMITTED)
        session.page(table)
        var preview = updateCode("must not overwrite null")
        external("UPDATE dbo.ui_large_text_v0122 SET gps_location=N'' WHERE id=1")
        check(session.confirm(preview.token).status == MutationStatus.CONFLICT)
        session.page(table)
        preview = updateCode("must not overwrite empty")
        check("nvarchar(max)" in preview.sql && "varchar(max)" in preview.sql && "2147483647" !in preview.sql)
        external("UPDATE dbo.ui_large_text_v0122 SET gps_location=N' ' WHERE id=1")
        check(session.confirm(preview.token).status == MutationStatus.CONFLICT)
        external("UPDATE dbo.ui_large_text_v0122 SET gps_location=N'A',note='Original' WHERE id=1")
        session.page(table)
        preview = updateCode("must not overwrite case")
        external("UPDATE dbo.ui_large_text_v0122 SET gps_location=N'a' WHERE id=1")
        check(session.confirm(preview.token).status == MutationStatus.CONFLICT)
        session.page(table)
        preview = updateCode("must not overwrite varchar")
        external("UPDATE dbo.ui_large_text_v0122 SET note='Original ' WHERE id=1")
        check(session.confirm(preview.token).status == MutationStatus.CONFLICT)
        session.page(table)
        preview = updateCode("must not overwrite zero")
        external("UPDATE dbo.ui_large_text_v0122 SET gps_location=N'a'+NCHAR(0) WHERE id=1")
        check(session.confirm(preview.token).status == MutationStatus.CONFLICT)
        session.page(table)
        val text = "数舟😀经纬度 ".repeat(300) // More than the old 2048-character read budget.
        preview = session.preview(MutationKind.UPDATE, 0, mapOf("gps_location" to CellInput(text)))
        check(session.confirm(preview.token).status == MutationStatus.COMMITTED)
        page = session.page(table)
        check(page.rows.single()[2] == DbValue.Text(text))
        check(session.confirm(updateCode("0009").token).status == MutationStatus.COMMITTED)
        external("UPDATE dbo.ui_large_text_v0122 SET gps_location=REPLICATE(N'x',3999)+N'A' WHERE id=1")
        page = session.page(table)
        check((page.rows.single()[2] as DbValue.Text).value.length == 4000)
        preview = session.preview(MutationKind.DELETE, 0, emptyMap())
        external("UPDATE dbo.ui_large_text_v0122 SET gps_location=REPLICATE(N'x',3999)+N'B' WHERE id=1")
        check(session.confirm(preview.token).status == MutationStatus.CONFLICT)
        external("UPDATE dbo.ui_large_text_v0122 SET gps_location=REPLICATE(CONVERT(nvarchar(max),N'x'),4001) WHERE id=1; INSERT dbo.ui_large_text_v0122 VALUES(2,N'other',N'短值',NULL,NULL)")
        page = session.page(table)
        check(page.rows[0][2] is DbValue.Unavailable)
        check(runCatching { updateCode("truncated write") }.exceptionOrNull() is WorkspaceRejected)
        check(runCatching { session.preview(MutationKind.DELETE, 0, emptyMap()) }.exceptionOrNull() is WorkspaceRejected)
        preview = session.preview(MutationKind.UPDATE, 1, mapOf("emp_code" to CellInput("other edited")))
        check(session.confirm(preview.token).status == MutationStatus.COMMITTED)
        session.page(table)
        preview = session.preview(MutationKind.INSERT, null, mapOf("id" to CellInput("3"), "emp_code" to CellInput("new"), "gps_location" to CellInput(isNull = true)))
        check(session.confirm(preview.token).status == MutationStatus.COMMITTED)
        page = session.page(table)
        preview = session.preview(MutationKind.DELETE, page.rows.indexOfFirst { it[0] == DbValue.Integer(3) }, emptyMap())
        check(session.confirm(preview.token).status == MutationStatus.COMMITTED)
        session.setWritable(false)
    }
}
