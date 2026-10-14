package com.example.datasail.core

import java.sql.ResultSet
import java.sql.Types

internal object JdbcResultReader {
    fun read(rows: ResultSet, offset: Int, schema: TableSchema?, exactFloating: Boolean = false): ResultPage {
        val meta = rows.metaData
        val columnCount = minOf(meta.columnCount, 64)
        val columns = (1..columnCount).map { ResultColumn(meta.getColumnLabel(it), meta.getColumnTypeName(it), meta.getColumnType(it)) }
        val values = mutableListOf<List<DbValue>>()
        var remainingChars = 262_144
        var more = false
        while (rows.next()) {
            if (values.size == 100 || remainingChars <= 0) { more = true; break }
            values += columns.mapIndexed { index, column ->
                val value = when (column.jdbcType) {
                    Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                        if (!meta.isSigned(index + 1) && column.jdbcType == Types.BIGINT)
                            rows.getBigDecimal(index + 1)?.let(DbValue::Decimal) ?: DbValue.Null
                        else rows.getLong(index + 1).let { if (rows.wasNull()) DbValue.Null else DbValue.Integer(it) }
                    Types.NUMERIC, Types.DECIMAL -> if (meta.getPrecision(index + 1) in 1..2048) {
                        rows.getBigDecimal(index + 1)?.let { number ->
                            val size = number.precision().toLong() + kotlin.math.abs(number.scale().toLong()) + 3
                            if (size > remainingChars) { remainingChars = 0; DbValue.Unavailable("数值超出读取预算") }
                            else { remainingChars -= size.toInt(); DbValue.Decimal(number) }
                        } ?: DbValue.Null
                    } else rows.getCharacterStream(index + 1)?.use { reader ->
                        val limit = minOf(2048, remainingChars.coerceAtLeast(0))
                        val buffer = CharArray(limit + 1); var count = 0
                        while (count < buffer.size) { val n = reader.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                        remainingChars -= count
                        if (count > limit) DbValue.Unavailable("数值超出读取预算") else {
                            val text = String(buffer, 0, count)
                            if (text in setOf("NaN", "Infinity", "-Infinity")) DbValue.Text(text)
                            else DbValue.Decimal(java.math.BigDecimal(text))
                        }
                    } ?: DbValue.Null
                    Types.BIT, Types.BOOLEAN -> if (column.jdbcType == Types.BIT && meta.getPrecision(index + 1) > 1) DbValue.Unavailable("多位 BIT 未加载") else rows.getBoolean(index + 1).let { if (rows.wasNull()) DbValue.Null else DbValue.Bool(it) }
                    Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, -155 ->
                        rows.getString(index + 1)?.let(DbValue::Temporal) ?: DbValue.Null
                    Types.FLOAT, Types.REAL, Types.DOUBLE -> if (!exactFloating) rows.getString(index + 1)?.let(DbValue::Approximate) ?: DbValue.Null
                    else {
                        val single = column.jdbcType == Types.REAL
                        val number = if (single) rows.getFloat(index + 1).toDouble() else rows.getDouble(index + 1)
                        if (rows.wasNull()) DbValue.Null else if (!number.isFinite()) DbValue.Unavailable("非有限浮点值不能安全编辑")
                        else DbValue.Approximate(if (single) number.toFloat().toString() else number.toString())
                    }
                    Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> rows.getBinaryStream(index + 1)?.use { stream ->
                        val buffer = ByteArray(257); var count = 0
                        while (count < buffer.size) { val n = stream.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                        if (count > 256) DbValue.Unavailable("二进制超过 256 字节，未加载") else DbValue.Binary(buffer.copyOf(count))
                    } ?: DbValue.Null
                    Types.OTHER, Types.JAVA_OBJECT, Types.SQLXML, Types.ARRAY, Types.STRUCT -> DbValue.Unavailable("类型 ${column.typeName} 未加载")
                    else -> rows.getCharacterStream(index + 1)?.use { reader ->
                        val limit = minOf(2048, remainingChars.coerceAtLeast(0))
                        val buffer = CharArray(limit + 1); var count = 0
                        while (count < buffer.size) { val n = reader.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                        remainingChars -= count
                        if (count > limit) DbValue.Unavailable("长值超出读取预算") else {
                            val text = String(buffer, 0, count)
                            DbValue.Text(text)
                        }
                    } ?: DbValue.Null
                }
                value
            }
        }
        return ResultPage(columns, values, more, offset, schema, schema?.keys?.isNotEmpty() == true, totalColumns = meta.columnCount)
    }
}
