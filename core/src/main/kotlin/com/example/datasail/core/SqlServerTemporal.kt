package com.example.datasail.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.sql.Types

/** Exact text transport: no device timezone conversion, precision rounding or Android JDBC temporal overloads. */
internal object SqlServerTemporal {
    val types = setOf(Types.DATE, Types.TIME, Types.TIMESTAMP, -155)
    fun sqlType(column: ColumnDef): String = when (column.typeName.lowercase()) {
        "date", "datetime", "smalldatetime" -> column.typeName.lowercase()
        "time", "datetime2", "datetimeoffset" -> "${column.typeName.lowercase()}(${column.scale.coerceIn(0, 7)})"
        else -> throw WorkspaceRejected("日期类型尚未支持")
    }
    fun parse(column: ColumnDef, text: String): DbValue.Temporal {
        try {
            val normalized = text.trim().replaceFirst(' ', 'T').replace(" +", "+").replace(" -", "-")
            val type = column.typeName.lowercase()
            var date: LocalDate? = null
            var time: LocalTime? = null
            val value = when (type) {
                "date" -> LocalDate.parse(normalized).also { date = it }.toString()
                "time" -> LocalTime.parse(normalized).also { time = it }.format(DateTimeFormatter.ISO_LOCAL_TIME)
                "datetimeoffset" -> OffsetDateTime.parse(normalized).also {
                    date = it.toLocalDate(); time = it.toLocalTime()
                    if (kotlin.math.abs(it.offset.totalSeconds) > 14 * 3600 || it.offset.totalSeconds % 60 != 0 || it.withOffsetSameInstant(java.time.ZoneOffset.UTC).year !in 1..9999) throw WorkspaceRejected("时区超出 SQL Server 范围")
                }.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                "datetime", "datetime2", "smalldatetime" -> LocalDateTime.parse(normalized).also { date = it.toLocalDate(); time = it.toLocalTime() }.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                else -> throw WorkspaceRejected("日期类型尚未支持")
            }
            if (date != null && date!!.year !in 1..9999) throw WorkspaceRejected("日期年份需在 0001–9999")
            val nano = time?.nano ?: 0
            when (type) {
                "datetime" -> if (date!! < LocalDate.of(1753, 1, 1) || nano % 1_000_000 != 0 || (nano / 1_000_000) % 10 !in setOf(0, 3, 7)) throw WorkspaceRejected("datetime 需使用 .000/.003/.007 秒精度且年份至少 1753；不自动舍入")
                "smalldatetime" -> if (date!! < LocalDate.of(1900, 1, 1) || date!! > LocalDate.of(2079, 6, 6) || time!!.second != 0 || nano != 0) throw WorkspaceRejected("smalldatetime 为分钟精度，日期范围 1900-01-01 至 2079-06-06")
                else -> if (time != null && nano % Math.pow(10.0, (9 - column.scale.coerceIn(0, 7)).toDouble()).toInt() != 0) throw WorkspaceRejected("时间超过字段小数精度；未自动舍入")
            }
            return DbValue.Temporal(value)
        } catch (_: DateTimeParseException) { throw WorkspaceRejected("日期格式不正确，请使用 ISO 日期/时间，datetimeoffset 须含时区") }
    }
}
