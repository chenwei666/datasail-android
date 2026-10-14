package com.example.datasail.core

import java.math.BigDecimal
import java.sql.Types

data class TableRef(val catalog: String, val schema: String, val name: String, val objectId: Int, val view: Boolean) {
    val label get() = "$schema.$name"
    val qualified get() = "${SqlNames.quote(schema)}.${SqlNames.quote(name)}"
}
data class ColumnDef(val name: String, val jdbcType: Int, val typeName: String, val nullable: Boolean,
    val size: Int, val scale: Int, val identity: Boolean, val generated: Boolean, val primaryKeyOrder: Int = 0,
    val hasDefault: Boolean = false) {
    val rowVersion get() = typeName.equals("rowversion", true) || (typeName.equals("timestamp", true) && jdbcType == Types.BINARY)
    val editable get() = !identity && !generated && !rowVersion && jdbcType in EDITABLE_TYPES
    companion object {
        val EDITABLE_TYPES = setOf(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR,
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.DECIMAL, Types.NUMERIC, Types.BIT, Types.BOOLEAN, Types.DATE, Types.TIME, Types.TIMESTAMP, -155)
    }
}
data class TableSchema(val table: TableRef, val columns: List<ColumnDef>, val hasTriggers: Boolean, val readOnlyOverride: String? = null) {
    val keys get() = columns.filter { it.primaryKeyOrder > 0 }.sortedBy { it.primaryKeyOrder }
    val version get() = columns.firstOrNull { it.rowVersion }
    val readOnlyReason: String? get() = when {
        readOnlyOverride != null -> readOnlyOverride
        table.view -> "视图只读"
        keys.isEmpty() -> "没有主键，不能安全定位单行"
        hasTriggers -> "存在启用的 DML 触发器，本版禁止图形编辑以避免未知副作用"
        columns.size > 64 -> "列数超过本版安全编辑范围"
        keys.any { it.jdbcType !in ColumnDef.EDITABLE_TYPES } -> "主键类型尚未支持编辑定位"
        version == null && columns.any { it.jdbcType !in ColumnDef.EDITABLE_TYPES || it.size > 4000 } -> "无 rowversion 且包含不能精确比较的列，保持只读"
        else -> null
    }
}
data class ResultColumn(val name: String, val typeName: String, val jdbcType: Int)
data class ResultPage(val columns: List<ResultColumn>, val rows: List<List<DbValue>>, val hasMore: Boolean,
    val offset: Int = 0, val tableSchema: TableSchema? = null, val stableOrder: Boolean = false, val columnOffset: Int = 0, val totalColumns: Int = columns.size, val view: TableView = TableView())
enum class MutationKind(val label: String) { INSERT("新增"), UPDATE("修改"), DELETE("删除") }
data class CellInput(val text: String = "", val isNull: Boolean = false)
data class ChangePreview(val column: String, val before: String?, val after: String?)
data class MutationPreview(val token: String, val kind: MutationKind, val target: String, val sql: String,
    val changes: List<ChangePreview>, val keyDescription: String, val expiresAtMillis: Long)
enum class MutationStatus { COMMITTED, CONFLICT, ROLLED_BACK, UNKNOWN }
data class MutationResult(val status: MutationStatus, val message: String)
class WorkspaceRejected(val explanation: String) : IllegalArgumentException(explanation)

object SqlNames {
    fun quote(identifier: String): String {
        require(identifier.isNotEmpty() && identifier.length <= 128 && '\u0000' !in identifier)
        return "[${identifier.replace("]", "]]")}]"
    }
}
object Values {
    fun display(value: DbValue): String = when (value) {
        DbValue.Null -> "NULL"
        is DbValue.Text -> "\"" + value.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is DbValue.Integer -> value.value.toString()
        is DbValue.Decimal -> value.value.toPlainString()
        is DbValue.Bool -> value.value.toString()
        is DbValue.Temporal -> value.value
        is DbValue.Approximate -> value.value
        is DbValue.Binary -> "0x" + value.copyBytes().joinToString("") { "%02x".format(it) }
        is DbValue.Unavailable -> "[${value.reason}]"
    }
    fun parse(column: ColumnDef, input: CellInput): DbValue {
        if (!column.editable) throw WorkspaceRejected("该列为只读或类型暂不支持")
        if (input.text.length > 4000) throw WorkspaceRejected("输入超过本版 4000 字符限制；未截断或提交")
        if (input.isNull) {
            if (!column.nullable) throw WorkspaceRejected("${column.name} 不允许 NULL")
            return DbValue.Null
        }
        return try {
            when (column.jdbcType) {
                Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> {
                    if (input.text.length > minOf(column.size, 4000)) throw WorkspaceRejected("${column.name} 超过字段长度或本版 4000 字符限制")
                    DbValue.Text(input.text)
                }
                Types.DATE, Types.TIME, Types.TIMESTAMP, -155 -> SqlServerTemporal.parse(column, input.text)
                Types.BIT, Types.BOOLEAN -> when (input.text.lowercase()) {
                    "true", "1" -> DbValue.Bool(true)
                    "false", "0" -> DbValue.Bool(false)
                    else -> throw WorkspaceRejected("布尔值使用 true/false 或 1/0")
                }
                Types.DECIMAL, Types.NUMERIC -> {
                    val decimal = BigDecimal(input.text).setScale(column.scale, java.math.RoundingMode.UNNECESSARY)
                    if (decimal.precision() > column.size) throw WorkspaceRejected("小数精度超出字段定义")
                    DbValue.Decimal(decimal)
                }
                else -> {
                    val number = input.text.toLong()
                    val range = when (column.jdbcType) {
                        Types.TINYINT -> 0L..255L
                        Types.SMALLINT -> Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
                        Types.INTEGER -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                        else -> Long.MIN_VALUE..Long.MAX_VALUE
                    }
                    if (number !in range) throw WorkspaceRejected("整数超出字段范围")
                    DbValue.Integer(number)
                }
            }
        } catch (_: NumberFormatException) { throw WorkspaceRejected("${column.name} 数值格式不正确") }
        catch (_: ArithmeticException) { throw WorkspaceRejected("${column.name} 小数不能无损写入") }
    }
}

/** Column windows preserve metadata order and keep each fetch within the existing 64-column budget. */
object TableColumns {
    fun window(schema: TableSchema, offset: Int): List<ColumnDef> {
        require(offset >= 0 && offset % 64 == 0 && offset < schema.columns.size)
        return schema.columns.drop(offset).take(64)
    }
}
