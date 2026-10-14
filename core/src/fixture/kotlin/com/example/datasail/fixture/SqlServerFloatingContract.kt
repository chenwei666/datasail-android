package com.example.datasail.fixture

import com.example.datasail.core.*
import java.sql.Connection

object SqlServerFloatingContract {
    fun createTable(connection: Connection) {
        connection.createStatement().use { it.execute("""
            DROP TABLE IF EXISTS dbo.ui_floating_v0121;
            CREATE TABLE dbo.ui_floating_v0121(id int PRIMARY KEY, code nvarchar(30) NOT NULL,
                punch_time datetime2(7) NOT NULL, longitude float(53) NULL, latitude real NULL);
            INSERT dbo.ui_floating_v0121 VALUES(1,N'0007','2024-02-29T12:34:56.1234567',1.0000000000000002,1.0000001192092896);
        """.trimIndent()) }
    }
    fun verify(session: SqlServerSession, external: (String) -> Unit) {
        val table = session.tables("dbo").single { it.name == "ui_floating_v0121" }
        var page = session.page(table)
        check(page.tableSchema!!.version == null && page.tableSchema!!.readOnlyReason == null)
        check(page.rows.single()[3] == DbValue.Approximate("1.0000000000000002"))
        check(page.rows.single()[4] == DbValue.Approximate("1.0000001"))
        session.setWritable(true)
        var preview = session.preview(MutationKind.UPDATE, 0, mapOf("code" to CellInput("0008")))
        check("CONVERT(float(53),?)" in preview.sql && "CONVERT(real,?)" in preview.sql)
        check(session.confirm(preview.token).status == MutationStatus.COMMITTED)
        session.page(table)
        preview = session.preview(MutationKind.UPDATE, 0, mapOf("code" to CellInput("must not overwrite")))
        external("UPDATE dbo.ui_floating_v0121 SET longitude=1.0000000000000004 WHERE id=1")
        check(session.confirm(preview.token).status == MutationStatus.CONFLICT)
        session.page(table)
        preview = session.preview(MutationKind.DELETE, 0, emptyMap())
        external("UPDATE dbo.ui_floating_v0121 SET latitude=1.0000002384185791 WHERE id=1")
        check(session.confirm(preview.token).status == MutationStatus.CONFLICT)
        session.page(table)
        preview = session.preview(MutationKind.UPDATE, 0, mapOf("longitude" to CellInput("113.12345678901234"), "latitude" to CellInput("22.12345")))
        check(session.confirm(preview.token).status == MutationStatus.COMMITTED)
        page = session.page(table)
        check(page.rows.single()[1] == DbValue.Text("0008"))
        check(page.rows.single()[3] == DbValue.Approximate("113.12345678901234"))
        check(page.rows.single()[4] == DbValue.Approximate("22.12345"))
        preview = session.preview(MutationKind.UPDATE, 0, mapOf("longitude" to CellInput(isNull = true)))
        check(session.confirm(preview.token).status == MutationStatus.COMMITTED)
        check(session.page(table).rows.single()[3] == DbValue.Null)
        preview = session.preview(MutationKind.UPDATE, 0, mapOf("code" to CellInput("null conflict")))
        external("UPDATE dbo.ui_floating_v0121 SET longitude=0 WHERE id=1")
        check(session.confirm(preview.token).status == MutationStatus.CONFLICT)
        session.page(table)
        preview = session.preview(MutationKind.INSERT, null, mapOf("id" to CellInput("2"), "code" to CellInput("new"),
            "punch_time" to CellInput("2024-02-29T12:34:56.1234567"), "longitude" to CellInput("0.1"), "latitude" to CellInput("0.1")))
        check(session.confirm(preview.token).status == MutationStatus.COMMITTED)
        page = session.page(table)
        preview = session.preview(MutationKind.DELETE, page.rows.indexOfFirst { it[0] == DbValue.Integer(2) }, emptyMap())
        check(session.confirm(preview.token).status == MutationStatus.COMMITTED)
    }
}
