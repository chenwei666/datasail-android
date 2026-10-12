package com.example.datasail.core

import java.io.Writer

/** Export is an explicit snapshot of the loaded page, not a database backup. */
object PageCsv {
    fun canExport(page: ResultPage) = page.rows.none { row -> row.any { it is DbValue.Unavailable } }
    fun write(page: ResultPage, output: Writer) {
        if (!canExport(page)) throw WorkspaceRejected("存在未完整加载的字段，不能导出此页")
        fun text(value: String): String {
            // Reversible backslash escape distinguishes literal \N from SQL NULL.
            val escaped = if (value.startsWith("\\")) "\\$value" else value
            // CSV quoting alone does not stop spreadsheet formula evaluation.
            return if (escaped.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || escaped.firstOrNull() in listOf('\t', '\r', '\n')) "'$escaped" else escaped
        }
        fun line(cells: List<String>) {
            output.write(cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }); output.write("\r\n")
        }
        line(page.columns.map { text(it.name) })
        page.rows.forEach { row -> line(row.map { value -> when (value) {
            DbValue.Null -> "\\N"
            is DbValue.Text -> text(value.value)
            is DbValue.Integer -> value.value.toString()
            is DbValue.Decimal -> value.value.toPlainString()
            is DbValue.Bool -> value.value.toString()
            is DbValue.Temporal -> text(value.value)
            is DbValue.Approximate -> value.value
            is DbValue.Binary -> "0x" + value.copyBytes().joinToString("") { "%02x".format(it) }
            is DbValue.Unavailable -> error("Checked above")
        } }) }
    }
}
