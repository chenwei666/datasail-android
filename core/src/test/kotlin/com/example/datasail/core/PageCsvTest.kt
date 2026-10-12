package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.math.BigDecimal

class PageCsvTest {
    private fun page(vararg values: DbValue) = ResultPage(listOf(ResultColumn("value", "text", 12)), values.map { listOf(it) }, false)
    @Test fun quotingNullBinaryAndExactNumbersRemainDistinct() {
        val output = StringWriter()
        PageCsv.write(page(DbValue.Null, DbValue.Text(""), DbValue.Text("\\N"), DbValue.Text("0007"),
            DbValue.Text("a,\"b\"\r\nc"), DbValue.Integer(Long.MAX_VALUE), DbValue.Decimal(BigDecimal("12345678901234567890.001")), DbValue.Binary(byteArrayOf(0, -1))), output)
        assertEquals("\"value\"\r\n\"\\N\"\r\n\"\"\r\n\"\\\\N\"\r\n\"0007\"\r\n\"a,\"\"b\"\"\r\nc\"\r\n\"9223372036854775807\"\r\n\"12345678901234567890.001\"\r\n\"0x00ff\"\r\n", output.toString())
    }
    @Test fun spreadsheetFormulasAreNeutralizedAndIncompleteValuesCannotExport() {
        for (text in listOf("=1+1", "+cmd", "-cmd", "@sum(1)", "   =1", "\t=1", "\r=1")) {
            val out = StringWriter(); PageCsv.write(page(DbValue.Text(text)), out)
            assertTrue(out.toString().contains("\"'"))
        }
        val output = StringWriter()
        val incomplete = page(DbValue.Unavailable("not loaded"))
        assertFalse(PageCsv.canExport(incomplete))
        assertThrows(WorkspaceRejected::class.java) { PageCsv.write(incomplete, output) }
        assertEquals("", output.toString())
    }
}
