package com.example.datasail.core

import java.sql.Types

/** SQL Server metadata reports MAX as Int.MAX_VALUE; it is not a literal SQL length. */
internal object SqlServerText {
    fun sqlType(column: ColumnDef): String {
        val name = when (column.jdbcType) {
            Types.CHAR -> "char"
            Types.VARCHAR -> "varchar"
            Types.NCHAR -> "nchar"
            Types.NVARCHAR -> "nvarchar"
            else -> throw WorkspaceRejected("文本类型暂不能安全比较")
        }
        if (column.size == Int.MAX_VALUE && name in setOf("varchar", "nvarchar")) return "$name(max)"
        val maximum = if (name.startsWith("n")) 4000 else 8000
        if (column.size !in 1..maximum) throw WorkspaceRejected("文本字段长度无效，请刷新表结构")
        return "$name(${column.size})"
    }
}
