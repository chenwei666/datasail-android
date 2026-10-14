package com.example.datasail.core

import java.math.BigDecimal
import java.sql.Types

/** A view is always evaluated by the server, before its bounded result page is fetched. */
enum class FilterOperator(val label: String, val sql: String) {
    EQ("等于", "="), NE("不等于", "<>"), GT("大于", ">"), GE("大于等于", ">="),
    LT("小于", "<"), LE("小于等于", "<="), CONTAINS("包含", "LIKE"), STARTS_WITH("开头是", "LIKE"),
    IS_NULL("为空 NULL", "IS NULL"), NOT_NULL("非 NULL", "IS NOT NULL");
    val needsValue get() = this != IS_NULL && this != NOT_NULL
}
data class ColumnFilter(val column: String, val operator: FilterOperator, val value: String = "")
data class ColumnSort(val column: String, val descending: Boolean = false)
data class TableView(val filters: List<ColumnFilter> = emptyList(), val sorts: List<ColumnSort> = emptyList())
data class CompiledTableView(val where: String, val order: String, val parameters: List<Any>)

object TableViews {
    fun compile(schema: TableSchema, view: TableView, quote: (String) -> String, sqlite: Boolean = false, sqlServer: Boolean = false): CompiledTableView {
        if (view.filters.size > 8 || view.sorts.size > 3) throw WorkspaceRejected("最多 8 个筛选条件、3 个排序字段")
        fun column(name: String) = schema.columns.singleOrNull { it.name == name }
            ?: throw WorkspaceRejected("字段不存在，请刷新表结构")
        val parameters = mutableListOf<Any>()
        val predicates = view.filters.map { filter ->
            val definition = column(filter.column)
            val name = quote(definition.name)
            if (!filter.operator.needsValue) "$name ${filter.operator.sql}" else {
                if (filter.value.length > 4000) throw WorkspaceRejected("筛选值超过 4000 字符")
                val like = filter.operator == FilterOperator.CONTAINS || filter.operator == FilterOperator.STARTS_WITH
                val textType = definition.jdbcType in setOf(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR)
                if (like) {
                    if (!textType && !sqlite) throw WorkspaceRejected("包含和开头筛选仅用于文本列")
                    // Escape LIKE syntax too: user values never become a pattern or SQL syntax.
                    val literal = filter.value.replace("!", "!!").replace("%", "!%").replace("_", "!_").replace("[", "![")
                    parameters += (if (filter.operator == FilterOperator.CONTAINS) "%" else "") + literal + "%"
                } else {
                    parameters += try {
                        when {
                            sqlServer && definition.jdbcType in SqlServerTemporal.types -> SqlServerTemporal.parse(definition, filter.value).value
                            sqlite || textType -> filter.value
                            definition.jdbcType in setOf(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT) -> filter.value.toLong()
                            definition.jdbcType in setOf(Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.FLOAT, Types.DOUBLE) -> BigDecimal(filter.value).also { require(it.precision() <= 1000 && kotlin.math.abs(it.scale().toLong()) <= 1000) }
                            definition.jdbcType in setOf(Types.BIT, Types.BOOLEAN) -> when (filter.value.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> throw IllegalArgumentException() }
                            definition.jdbcType == Types.DATE -> java.sql.Date.valueOf(java.time.LocalDate.parse(filter.value).toString())
                            definition.jdbcType == Types.TIME -> java.time.LocalTime.parse(filter.value).let { require(it.nano == 0); java.sql.Time.valueOf(it.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))) }
                            definition.jdbcType == Types.TIMESTAMP -> java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(filter.value.replace(' ', 'T')).toString().replace('T', ' ').let { if (it.length == 16) "$it:00" else it })
                            definition.jdbcType == -155 -> java.time.OffsetDateTime.parse(filter.value).toString()
                            else -> throw WorkspaceRejected("此列类型暂不支持值筛选，可使用 NULL 筛选")
                        }
                    } catch (e: WorkspaceRejected) { throw e } catch (_: java.time.DateTimeException) { throw WorkspaceRejected("${definition.name} 日期时间格式不正确，请输入有效 ISO 日期时间") } catch (_: IllegalArgumentException) { throw WorkspaceRejected("${definition.name} 筛选值格式不正确，请按字段类型输入") }
                }
                val parameter = if (sqlServer && definition.jdbcType in SqlServerTemporal.types) "CONVERT(${SqlServerTemporal.sqlType(definition)},?,126)" else "?"
                "$name ${filter.operator.sql} $parameter" + if (like) " ESCAPE '!'" else ""
            }
        }
        if (view.sorts.map { it.column }.distinct().size != view.sorts.size) throw WorkspaceRejected("排序字段重复")
        val ordered = view.sorts.map { column(it.column); quote(it.column) + if (it.descending) " DESC" else " ASC" } +
            schema.keys.filterNot { key -> view.sorts.any { it.column == key.name } }.map { quote(it.name) + " ASC" }
        return CompiledTableView(if (predicates.isEmpty()) "" else " WHERE " + predicates.joinToString(" AND "),
            if (ordered.isEmpty()) "" else " ORDER BY " + ordered.joinToString(","), parameters)
    }
}
