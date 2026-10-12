package com.example.datasail.core

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.util.UUID

/** Owned by WorkspaceRunner's single worker. UI code never receives JDBC objects. */
class SqlServerSession(private val connection: Connection, val profile: ConnectionProfile,
    private val statementChanged: (Statement?) -> Unit = {}, private val now: () -> Long = System::currentTimeMillis) : AutoCloseable {
    val id: String = UUID.randomUUID().toString()
    var catalog: String = profile.database; private set
    var writable: Boolean = false; private set
    private var currentPage: ResultPage? = null
    private var pending: Plan? = null
    private var closed = false
    private data class Param(val column: ColumnDef, val value: DbValue)
    private data class Plan(val preview: MutationPreview, val schema: TableSchema, val parameters: List<Param>)

    init {
        require(profile.kind == DatabaseKind.SQL_SERVER)
        connection.createStatement().use {
            statementChanged(it)
            try { it.queryTimeout = 5; it.execute("SET XACT_ABORT ON; SET NOCOUNT OFF; SET LOCK_TIMEOUT 5000") }
            finally { statementChanged(null) }
        }
    }
    fun catalogs(): List<String> = names("SELECT name FROM sys.databases WHERE HAS_DBACCESS(name)=1 AND state=0 ORDER BY name")
    fun schemas(): List<String> = names("SELECT name FROM sys.schemas WHERE schema_id<16384 ORDER BY name")
    fun switchCatalog(name: String) {
        if (name !in catalogs()) throw WorkspaceRejected("数据库不在当前账号可访问列表中")
        invalidate(); writable = false
        try {
            connection.catalog = name
            val actual = connection.catalog
            if (actual != name) throw WorkspaceRejected("驱动未确认目标数据库，连接已失效")
            catalog = actual
        } catch (error: Exception) {
            close() // A failed USE may already have changed server state; never retain an ambiguous target.
            throw error
        }
    }
    fun setWritable(enabled: Boolean) {
        if (enabled && profile.environment != ConnectionEnvironment.TEST) throw WorkspaceRejected("生产连接在 V0.2.0 中保持只读")
        pending = null
        writable = enabled
    }
    fun tables(schema: String): List<TableRef> {
        invalidate()
        return query("SELECT s.name,o.name,o.object_id,o.type FROM sys.objects o JOIN sys.schemas s ON o.schema_id=s.schema_id WHERE o.is_ms_shipped=0 AND o.type IN ('U','V') AND s.name=? ORDER BY o.name", listOf(schema)) { rows ->
            val result = mutableListOf<TableRef>()
            while (rows.next()) {
                if (result.size == 1000) throw WorkspaceRejected("该 Schema 超过 1000 个对象，本版不加载更多")
                result += TableRef(catalog, rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4).trim() == "V")
            }
            result
        }
    }
    fun describe(table: TableRef): TableSchema {
        if (table.catalog != catalog) throw WorkspaceRejected("表不属于当前数据库")
        val verified = query("SELECT s.name,o.name,o.type FROM sys.objects o JOIN sys.schemas s ON o.schema_id=s.schema_id WHERE o.object_id=? AND o.type IN ('U','V')", listOf(table.objectId)) { rows ->
            rows.next() && rows.getString(1) == table.schema && rows.getString(2) == table.name && (rows.getString(3).trim() == "V") == table.view
        }
        if (!verified) throw WorkspaceRejected("对象已变化或不可见，请刷新对象列表")
        val columns = query("""
            SELECT c.name,t.name,c.max_length,c.precision,c.scale,c.is_nullable,c.is_identity,
                c.is_computed,c.generated_always_type,c.default_object_id,COALESCE(pk.key_ordinal,0)
            FROM sys.columns c JOIN sys.types t ON c.user_type_id=t.user_type_id
            LEFT JOIN (SELECT ic.object_id,ic.column_id,ic.key_ordinal FROM sys.index_columns ic
              JOIN sys.indexes ix ON ix.object_id=ic.object_id AND ix.index_id=ic.index_id WHERE ix.is_primary_key=1) pk
              ON pk.object_id=c.object_id AND pk.column_id=c.column_id
            WHERE c.object_id=? AND c.is_hidden=0 ORDER BY c.column_id
        """.trimIndent(), listOf(table.objectId)) { rows ->
            val defs = mutableListOf<ColumnDef>()
            while (rows.next()) {
                if (defs.size >= 1024) throw WorkspaceRejected("列数超过支持范围")
                val type = rows.getString(2).lowercase()
                val jdbc = jdbcType(type)
                val length = rows.getInt(3)
                val size = if (jdbc in setOf(Types.DECIMAL, Types.NUMERIC)) rows.getInt(4)
                    else if (length == -1) Int.MAX_VALUE else if (type in setOf("nvarchar", "nchar")) length / 2 else length
                defs += ColumnDef(rows.getString(1), jdbc, type, rows.getBoolean(6), size, rows.getInt(5),
                    rows.getBoolean(7), rows.getBoolean(8) || rows.getInt(9) != 0, rows.getInt(11), rows.getInt(10) != 0)
            }
            defs
        }
        if (columns.isEmpty()) throw WorkspaceRejected("没有可见列，可能缺少元数据权限")
        val triggers = query("SELECT COUNT(*) FROM sys.triggers WHERE parent_id=? AND is_disabled=0", listOf(table.objectId)) { it.next(); it.getInt(1) > 0 }
        return TableSchema(table, columns, triggers)
    }
    fun page(table: TableRef, offset: Int = 0): ResultPage {
        invalidate()
        require(offset in 0..1_000_000)
        val schema = describe(table)
        val columns = schema.columns.take(64).joinToString(",") { SqlNames.quote(it.name) }
        if (schema.keys.isEmpty() && offset != 0) throw WorkspaceRejected("没有主键，不能保证稳定分页，仅显示首 100 行")
        val order = if (schema.keys.isEmpty()) "" else " ORDER BY " + schema.keys.joinToString(",") { SqlNames.quote(it.name) }
        val sql = if (schema.keys.isEmpty()) "SELECT TOP (101) $columns FROM ${table.qualified}"
            else "SELECT $columns FROM ${table.qualified}$order OFFSET ? ROWS FETCH NEXT 101 ROWS ONLY"
        val params = if (schema.keys.isEmpty()) emptyList() else listOf(offset)
        val result = query(sql, params, 101) { readPage(it, offset, schema) }
        currentPage = result
        return result
    }
    fun select(sql: String): ResultPage {
        invalidate()
        val checked = ReadQueryPolicy.validate(sql)
        return query(checked, limit = 101) { readPage(it, 0, null) }.also { currentPage = it }
    }
    fun preview(kind: MutationKind, rowIndex: Int?, inputs: Map<String, CellInput>): MutationPreview {
        pending = null
        if (!writable) throw WorkspaceRejected("连接处于只读模式")
        val page = currentPage ?: throw WorkspaceRejected("请先浏览目标表")
        val schema = page.tableSchema ?: throw WorkspaceRejected("查询、JOIN、聚合结果只读")
        schema.readOnlyReason?.let { throw WorkspaceRejected(it) }
        val original = if (kind == MutationKind.INSERT) null else (page.rows.getOrNull(rowIndex ?: -1)
            ?: throw WorkspaceRejected("请重新选择当前页中的记录"))
        if (original?.any { it is DbValue.Unavailable } == true) throw WorkspaceRejected("记录含截断/不支持的值，不能安全编辑")
        val defs = schema.columns.associateBy { it.name }
        val changes = linkedMapOf<ColumnDef, DbValue>()
        inputs.forEach { (name, input) ->
            val col = defs[name] ?: throw WorkspaceRejected("字段不在当前元数据中")
            if (kind != MutationKind.INSERT && col.primaryKeyOrder > 0) throw WorkspaceRejected("本版不修改主键")
            val parsed = Values.parse(col, input)
            val old = original?.get(schema.columns.indexOf(col))
            if (kind == MutationKind.INSERT || parsed != old) changes[col] = parsed
        }
        if (kind != MutationKind.DELETE && changes.isEmpty()) throw WorkspaceRejected("没有可提交的变更")
        if (kind == MutationKind.INSERT && schema.columns.any { !it.identity && !it.generated && !it.rowVersion && !it.nullable && !it.hasDefault && it !in changes })
            throw WorkspaceRejected("请填写所有无默认值的必填字段")
        val parameters = mutableListOf<Param>()
        fun bind(col: ColumnDef, value: DbValue): String { parameters += Param(col, value); return "?" }
        val whereColumns = if (schema.version != null) (schema.keys + schema.version!!).distinct() else schema.columns
        val where = if (original == null) "" else whereColumns.joinToString(" AND ") { col ->
            val value = original[schema.columns.indexOf(col)]
            if (value == DbValue.Null) "${SqlNames.quote(col.name)} IS NULL"
            else if (col.jdbcType in setOf(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR)) {
                // SQL string equality can ignore case and trailing spaces. Compare original bytes instead.
                "CONVERT(varbinary(max),${SqlNames.quote(col.name)})=CONVERT(varbinary(max),CONVERT(${col.typeName}(${col.size}),${bind(col, value)}))"
            } else "${SqlNames.quote(col.name)}=${bind(col, value)}"
        }
        val predicateParams = parameters.toList(); parameters.clear()
        val identityGuard = "OBJECT_ID(?)=?"
        val sql = when (kind) {
            MutationKind.INSERT -> "INSERT INTO ${schema.table.qualified} (${changes.keys.joinToString(",") { SqlNames.quote(it.name) }}) SELECT ${changes.entries.joinToString(",") { bind(it.key, it.value) }} WHERE $identityGuard"
            MutationKind.UPDATE -> "UPDATE ${schema.table.qualified} SET ${changes.entries.joinToString(",") { "${SqlNames.quote(it.key.name)}=${bind(it.key, it.value)}" }} WHERE $where AND $identityGuard"
            MutationKind.DELETE -> "DELETE FROM ${schema.table.qualified} WHERE $where AND $identityGuard"
        }
        parameters += predicateParams
        parameters += Param(ColumnDef("object", Types.NVARCHAR, "nvarchar", false, 260, 0, false, false), DbValue.Text(schema.table.qualified))
        parameters += Param(ColumnDef("id", Types.INTEGER, "int", false, 10, 0, false, false), DbValue.Integer(schema.table.objectId.toLong()))
        val preview = MutationPreview(UUID.randomUUID().toString(), kind,
            "${profile.host}:${profile.port} / $catalog / ${schema.table.label} / ${profile.environment.label}", sql,
            if (kind == MutationKind.DELETE) schema.columns.mapIndexed { i, c -> ChangePreview(c.name, Values.display(original!![i]), null) }
            else changes.map { (col, value) -> ChangePreview(col.name, original?.let { Values.display(it[schema.columns.indexOf(col)]) }, Values.display(value)) },
            if (original == null) "新记录" else schema.keys.joinToString("; ") { "${it.name}=${Values.display(original[schema.columns.indexOf(it)])}" }, now() + 120_000)
        pending = Plan(preview, schema, parameters.toList())
        return preview
    }
    fun discardPreview() { pending = null }
    fun confirm(token: String): MutationResult {
        val plan = pending ?: throw WorkspaceRejected("确认已失效，请重新预览")
        pending = null // Single use, including failure and unknown outcome. Never replay.
        if (!writable || plan.preview.token != token || now() > plan.preview.expiresAtMillis) throw WorkspaceRejected("确认已失效，请重新预览")
        var commitAttempted = false
        try {
            connection.autoCommit = false
            // A short table lock also prevents a concurrent DDL/trigger change between validation and DML.
            query("SELECT TOP (1) 1 FROM ${plan.schema.table.qualified} WITH (TABLOCK,HOLDLOCK)") { while (it.next()) { } }
            if (describe(plan.schema.table) != plan.schema) throw WorkspaceRejected("表结构或主键已变化，请刷新后重新编辑")
            val affected = prepared(plan.preview.sql) { statement ->
                statement.maxRows = 0
                plan.parameters.forEachIndexed { i, param -> bindValue(statement, i + 1, param) }
                statement.executeUpdate()
            }
            if (affected != 1) {
                connection.rollback()
                connection.autoCommit = true
                invalidate()
                return MutationResult(MutationStatus.CONFLICT, "匹配行数为 $affected（预期 1）；已回滚，请刷新核对并发变化")
            }
            commitAttempted = true
            connection.commit()
            invalidate()
            try { connection.autoCommit = true } catch (_: Exception) { close() }
            return MutationResult(MutationStatus.COMMITTED, "服务端确认已提交 1 行；请刷新读取提交后的值")
        } catch (error: Exception) {
            val failure = Failures.classify(error)
            val uncertain = commitAttempted || failure.kind in setOf(ErrorKind.NETWORK, ErrorKind.TIMEOUT, ErrorKind.CANCELLED)
            var rolledBack = false
            if (!uncertain) try { connection.rollback(); rolledBack = true; connection.autoCommit = true } catch (_: Exception) { rolledBack = false }
            invalidate()
            if (uncertain || !rolledBack) {
                close()
                return MutationResult(MutationStatus.UNKNOWN, "执行结果未知，连接已失效；需核对服务端，不会自动重试")
            }
            return MutationResult(MutationStatus.ROLLED_BACK, "提交失败，事务已回滚：${failure.description}")
        }
    }
    fun isClosed() = closed
    private fun invalidate() { pending = null; currentPage = null }
    override fun close() {
        closed = true; writable = false; invalidate()
        try { connection.close() } catch (_: Exception) { /* session remains invalid */ }
    }
    private fun names(sql: String) = query(sql, limit = 201) { rows ->
        val names = mutableListOf<String>()
        while (rows.next()) { if (names.size == 200) throw WorkspaceRejected("对象数量超过本版加载限制"); names += rows.getString(1) }
        names.toList()
    }
    private fun <T> prepared(sql: String, block: (PreparedStatement) -> T): T {
        check(!closed) { "Session closed" }
        connection.prepareStatement(sql).use { statement ->
            statementChanged(statement)
            try { statement.queryTimeout = 10; return block(statement) } finally { statementChanged(null) }
        }
    }
    private fun <T> query(sql: String, params: List<Any> = emptyList(), limit: Int = 1001, block: (ResultSet) -> T) = prepared(sql) { statement ->
        statement.maxRows = limit
        params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use(block)
    }
    private fun readPage(rows: ResultSet, offset: Int, schema: TableSchema?): ResultPage {
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
                    Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> rows.getLong(index + 1).let { if (rows.wasNull()) DbValue.Null else DbValue.Integer(it) }
                    Types.NUMERIC, Types.DECIMAL -> rows.getBigDecimal(index + 1)?.let(DbValue::Decimal) ?: DbValue.Null
                    Types.BIT, Types.BOOLEAN -> rows.getBoolean(index + 1).let { if (rows.wasNull()) DbValue.Null else DbValue.Bool(it) }
                    Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, -155 ->
                        rows.getString(index + 1)?.let(DbValue::Temporal) ?: DbValue.Null
                    Types.FLOAT, Types.REAL, Types.DOUBLE -> rows.getString(index + 1)?.let(DbValue::Approximate) ?: DbValue.Null
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
        return ResultPage(columns, values, more, offset, schema, schema?.keys?.isNotEmpty() == true)
    }
    private fun bindValue(statement: PreparedStatement, index: Int, parameter: Param) {
        when (val value = parameter.value) {
            DbValue.Null -> statement.setNull(index, parameter.column.jdbcType)
            is DbValue.Text -> if (parameter.column.jdbcType in setOf(Types.NVARCHAR, Types.NCHAR)) statement.setNString(index, value.value) else statement.setString(index, value.value)
            is DbValue.Integer -> statement.setLong(index, value.value)
            is DbValue.Decimal -> statement.setBigDecimal(index, value.value)
            is DbValue.Bool -> statement.setBoolean(index, value.value)
            is DbValue.Binary -> statement.setBytes(index, value.copyBytes())
            else -> throw WorkspaceRejected("该类型不能安全绑定写入")
        }
    }
    companion object {
        fun jdbcType(type: String): Int = when (type) {
            "char" -> Types.CHAR; "varchar" -> Types.VARCHAR; "nchar" -> Types.NCHAR; "nvarchar" -> Types.NVARCHAR
            "tinyint" -> Types.TINYINT; "smallint" -> Types.SMALLINT; "int" -> Types.INTEGER; "bigint" -> Types.BIGINT
            "decimal", "money", "smallmoney" -> Types.DECIMAL; "numeric" -> Types.NUMERIC; "bit" -> Types.BIT
            "timestamp", "rowversion", "binary" -> Types.BINARY; "varbinary" -> Types.VARBINARY
            "date" -> Types.DATE; "time" -> Types.TIME; "datetime", "datetime2", "smalldatetime" -> Types.TIMESTAMP
            "datetimeoffset" -> -155; "float" -> Types.DOUBLE; "real" -> Types.REAL
            else -> Types.OTHER
        }
    }
}
