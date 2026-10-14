package com.example.datasail.core

import java.math.BigDecimal
import java.sql.Types

/** Round-trip floating values; never compare the driver's rounded display string. */
internal object SqlServerFloating {
    val types = setOf(Types.REAL, Types.FLOAT, Types.DOUBLE)
    fun single(column: ColumnDef) = column.jdbcType == Types.REAL || column.size == 4
    fun sqlType(column: ColumnDef) = if (single(column)) "real" else "float(53)"
    fun parse(column: ColumnDef, text: String): DbValue.Approximate {
        val number = if (single(column)) text.toFloat().toDouble() else text.toDouble()
        val minimum = if (single(column)) java.lang.Float.MIN_NORMAL.toDouble() else java.lang.Double.MIN_NORMAL
        if (!number.isFinite() || (number != 0.0 && kotlin.math.abs(number) < minimum))
            throw WorkspaceRejected("${column.name} 超出 SQL Server 浮点范围")
        val canonical = if (single(column)) number.toFloat().toString() else number.toString()
        if (BigDecimal(text).compareTo(BigDecimal(canonical)) != 0)
            throw WorkspaceRejected("${column.name} 精度超出该浮点类型可往返的范围；未舍入提交")
        return DbValue.Approximate(canonical)
    }
}
