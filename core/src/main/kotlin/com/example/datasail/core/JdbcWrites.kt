package com.example.datasail.core

import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

data class JdbcWritePlan(val preview: MutationPreview, val schema: TableSchema, val target: String, val parameters: List<DbValue>, val parameterColumns: List<ColumnDef>)

/** Bound values, metadata-only identifiers and complete original-row predicates. No arbitrary write SQL. */
object JdbcWrites {
    fun prepare(page: ResultPage, kind: MutationKind, row: Int?, inputs: Map<String, CellInput>, mysql: Boolean, profile: ConnectionProfile, sqlite: Boolean = false): JdbcWritePlan {
        val schema = page.tableSchema ?: throw WorkspaceRejected("查询结果只读，请打开目标表")
        schema.readOnlyReason?.let { throw WorkspaceRejected(it) }
        if (kind == MutationKind.DROP_TABLE) throw WorkspaceRejected("此引擎暂不支持整表删除，请使用单条记录操作")
        if (page.columnOffset != 0 || page.columns.size != schema.columns.size) throw WorkspaceRejected("必须完整加载全部字段")
        val old = if (kind == MutationKind.INSERT) null else page.rows.getOrNull(row ?: -1) ?: throw WorkspaceRejected("请先选择记录")
        if (old?.any { it is DbValue.Unavailable } == true) throw WorkspaceRejected("当前行未完整加载，不能安全修改")
        if (old != null && schema.keys.any { old[schema.columns.indexOf(it)] == DbValue.Null }) throw WorkspaceRejected("主键包含 NULL，无法唯一定位")
        val q = if (mysql) "`" else "\""
        fun quote(s: String): String { require(s.isNotEmpty() && '\u0000' !in s); return q + s.replace(q, q + q) + q }
        val target = quote(schema.table.schema) + "." + quote(schema.table.name)
        val changes = linkedMapOf<ColumnDef, DbValue>()
        inputs.forEach { (name, input) ->
            val column = schema.columns.singleOrNull { it.name == name } ?: throw WorkspaceRejected("字段已变化")
            if (!column.editable || (kind != MutationKind.INSERT && column.primaryKeyOrder > 0)) throw WorkspaceRejected("该字段不可修改")
            val value = parse(column, input)
            if (old == null || value != old[schema.columns.indexOf(column)]) changes[column] = value
        }
        if (kind != MutationKind.DELETE && changes.isEmpty()) throw WorkspaceRejected("没有变更")
        if (kind == MutationKind.INSERT && schema.columns.any { !it.identity && !it.generated && !it.nullable && !it.hasDefault && it !in changes }) throw WorkspaceRejected("请填写所有必填字段")
        val parameters = mutableListOf<DbValue>()
        val parameterColumns = mutableListOf<ColumnDef>()
        fun parameter(c: ColumnDef, v: DbValue): String {
            parameters += v; parameterColumns += c
            return if (v is DbValue.Temporal) when (c.jdbcType) {
                Types.DATE -> "CAST(? AS DATE)"
                Types.TIME -> "CAST(? AS TIME(${c.scale.coerceIn(0, 6)}))"
                else -> "CAST(? AS ${if (mysql) "DATETIME" else "TIMESTAMP"}(${c.scale.coerceIn(0, 6)}))"
            } else "?"
        }
        val sets = changes.entries.joinToString(",") { "${quote(it.key.name)}=${parameter(it.key, it.value)}" }
        val where = if (old == null) "" else schema.columns.mapIndexed { i, c ->
            val name = quote(c.name); val v = old[i]
            if (v == DbValue.Null) "$name IS NULL" else if (v is DbValue.Text) {
                val param = parameter(c, v)
                if (sqlite) "CAST($name AS BLOB)=CAST($param AS BLOB)" else if (mysql) "BINARY $name=BINARY $param" else "convert_to($name::text,'UTF8')=convert_to($param::text,'UTF8')"
            } else "$name=${parameter(c, v)}"
        }.joinToString(" AND ")
        val sql = when (kind) {
            MutationKind.UPDATE -> "UPDATE $target SET $sets WHERE $where"
            MutationKind.DELETE -> "DELETE FROM $target WHERE $where"
            MutationKind.INSERT -> {
                parameters.clear(); parameterColumns.clear()
                "INSERT INTO $target (${changes.keys.joinToString(",") { quote(it.name) }}) VALUES (${changes.entries.joinToString(",") { parameter(it.key, it.value) }})"
            }
            else -> throw WorkspaceRejected("操作不支持")
        }
        val preview = MutationPreview(UUID.randomUUID().toString(), kind, "${profile.kind.label} / ${schema.table.catalog} / ${schema.table.label}", sql,
            if (kind == MutationKind.DELETE) schema.columns.mapIndexed { i, c -> ChangePreview(c.name, Values.display(old!![i]), null) }
            else changes.map { (c, v) -> ChangePreview(c.name, old?.let { Values.display(it[schema.columns.indexOf(c)]) }, Values.display(v)) },
            if (old == null) "新记录" else schema.keys.joinToString { "${it.name}=${Values.display(old[schema.columns.indexOf(it)])}" }, System.currentTimeMillis() + 120000)
        return JdbcWritePlan(preview, schema, target, parameters, parameterColumns)
    }
    private fun parse(c: ColumnDef, input: CellInput): DbValue {
        if (input.text.length > 4000) throw WorkspaceRejected("输入超过 4000 字符")
        if (input.isNull) { if (!c.nullable) throw WorkspaceRejected("字段不允许 NULL"); return DbValue.Null }
        return try { when (c.jdbcType) {
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                val bits = when (c.jdbcType) { Types.TINYINT -> 8; Types.SMALLINT -> 16; Types.INTEGER -> if (c.typeName.contains("mediumint", true)) 24 else 32; else -> 64 }
                val number = input.text.toBigInteger(); val unsigned = c.typeName.contains("unsigned", true)
                val min = if (unsigned) java.math.BigInteger.ZERO else java.math.BigInteger.ONE.shiftLeft(bits - 1).negate()
                val max = java.math.BigInteger.ONE.shiftLeft(if (unsigned) bits else bits - 1).subtract(java.math.BigInteger.ONE)
                require(number in min..max)
                if (unsigned && bits == 64) DbValue.Decimal(number.toBigDecimal()) else DbValue.Integer(number.toLong())
            }
            Types.NUMERIC, Types.DECIMAL -> {
                val number = BigDecimal(input.text)
                if (c.size > 0 && c.size != Int.MAX_VALUE) {
                    val exact = number.setScale(c.scale, java.math.RoundingMode.UNNECESSARY); require(exact.precision() <= c.size); DbValue.Decimal(exact)
                } else { require(number.precision() <= 1000 && kotlin.math.abs(number.scale()) <= 1000); DbValue.Decimal(number) }
            }
            Types.DATE -> DbValue.Temporal(java.time.LocalDate.parse(input.text).toString())
            Types.TIME, Types.TIMESTAMP -> {
                val text = input.text.replace(' ', 'T')
                val nano = if (c.jdbcType == Types.TIME) java.time.LocalTime.parse(text).nano else java.time.LocalDateTime.parse(text).nano
                require(nano % Math.pow(10.0, (9 - c.scale.coerceIn(0, 6)).toDouble()).toInt() == 0)
                DbValue.Temporal(text.replace('T', ' '))
            }
            Types.REAL, Types.FLOAT, Types.DOUBLE -> Values.parse(c.copy(jdbcType = if (single(c)) Types.REAL else Types.DOUBLE), input)
            else -> Values.parse(c.copy(size = if (c.size <= 0) Int.MAX_VALUE else c.size), input)
        } } catch (e: WorkspaceRejected) { throw e } catch (_: Exception) { throw WorkspaceRejected("${c.name} 格式、精度或范围不正确，未提交") }
    }
    private fun single(c: ColumnDef) = c.jdbcType == Types.REAL || c.typeName.equals("float", true) || c.typeName == "float4"
    fun bind(statement: PreparedStatement, index: Int, value: DbValue, column: ColumnDef) {
        when (value) {
            DbValue.Null -> statement.setNull(index, Types.NULL)
            is DbValue.Text -> statement.setString(index, value.value)
            is DbValue.Integer -> statement.setLong(index, value.value)
            is DbValue.Decimal -> statement.setBigDecimal(index, value.value)
            is DbValue.Bool -> statement.setBoolean(index, value.value)
            is DbValue.Temporal -> statement.setString(index, value.value)
            is DbValue.Approximate -> if (single(column)) statement.setFloat(index, value.value.toFloat()) else statement.setDouble(index, value.value.toDouble())
            else -> throw WorkspaceRejected("无法无损绑定此字段")
        }
    }
}
