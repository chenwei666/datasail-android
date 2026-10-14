package com.example.datasail.core

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID

/** Server-enforced read-only transactions; every operation ends before returning to the UI. */
class JdbcBrowseSession(private val connection: Connection, override val profile: ConnectionProfile,
    private val statementChanged: (Statement?) -> Unit = {}) : SqlWorkspaceSession {
    override val id: String = UUID.randomUUID().toString()
    override var catalog: String = profile.database; private set
    override val writable = false
    private val mysql = profile.kind in setOf(DatabaseKind.MYSQL, DatabaseKind.MARIADB)
    init {
        require(mysql || profile.kind == DatabaseKind.POSTGRESQL)
        connection.createStatement().use { statement ->
            statementChanged(statement)
            try {
                statement.queryTimeout = 5
                statement.execute(if (mysql) "SET SESSION TRANSACTION READ ONLY" else "SET default_transaction_read_only = on")
            } finally { statementChanged(null) }
        }
        connection.isReadOnly = true
        connection.autoCommit = false
    }
    private fun <T> transaction(block: () -> T): T {
        if (isClosed()) throw WorkspaceRejected("连接已失效，请重新连接")
        var failure: Throwable? = null
        try {
            // Reassert on every operation: SELECT functions may change session defaults.
            connection.createStatement().use { statement ->
                statementChanged(statement)
                try { statement.queryTimeout = 5; statement.execute("SET TRANSACTION READ ONLY") }
                finally { statementChanged(null) }
            }
            return block()
        }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { connection.rollback() }
            catch (cleanup: Exception) { close(); if (failure == null) throw cleanup else failure.addSuppressed(cleanup) }
        }
    }
    private fun <T> collect(rows: ResultSet, limit: Int = 1000, read: (ResultSet) -> T?): List<T> = rows.use {
        val result = mutableListOf<T>()
        var count = 0
        while (it.next()) {
            if (++count > limit) throw WorkspaceRejected("元数据超过 $limit 项，请缩小连接范围")
            read(it)?.let(result::add)
        }
        result
    }
    override fun catalogs(): List<String> = transaction {
        if (mysql) collect(connection.metaData.catalogs, 200) { it.getString("TABLE_CAT") }.sorted()
        else listOf(catalog) // PostgreSQL databases require distinct connections.
    }
    override fun schemas(): List<String> = transaction {
        if (mysql) listOf(catalog)
        else metadata("SELECT schema_name FROM information_schema.schemata ORDER BY schema_name LIMIT 201") { rows ->
            collect(rows, 200) { it.getString(1) }
        }
    }
    override fun switchCatalog(name: String) {
        if (name !in catalogs()) throw WorkspaceRejected("数据库不在可访问列表；PostgreSQL 切库请使用另一个连接")
        if (name == catalog) return
        try {
            connection.catalog = name
            if (connection.catalog != name) throw WorkspaceRejected("驱动未确认目标数据库，连接已失效")
            catalog = name
        } catch (error: Exception) { close(); throw error }
    }
    private fun pattern(value: String): String {
        val escape = connection.metaData.searchStringEscape
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%")
    }
    override fun tables(schema: String): List<TableRef> {
        if (schema !in schemas()) throw WorkspaceRejected("Schema 不在当前连接中")
        return transaction {
            if (!mysql) return@transaction metadata("SELECT table_name,table_type FROM information_schema.tables WHERE table_schema=? AND table_type IN ('BASE TABLE','VIEW') ORDER BY table_name LIMIT 1001", listOf(schema)) { rows ->
                collect(rows) { TableRef(catalog, schema, it.getString(1), 0, it.getString(2) == "VIEW") }
            }
            collect(connection.metaData.getTables(catalog, null, "%", arrayOf("TABLE", "VIEW"))) { row ->
                if (row.getString("TABLE_CAT") != catalog || (!mysql && row.getString("TABLE_SCHEM") != schema)) null
                else TableRef(catalog, schema, row.getString("TABLE_NAME"), 0, row.getString("TABLE_TYPE") == "VIEW")
            }.sortedBy { it.name }
        }
    }
    override fun describe(table: TableRef): TableSchema {
        if (table.catalog != catalog || table !in tables(table.schema)) throw WorkspaceRejected("对象已变化或不可见，请刷新")
        return transaction {
            if (!mysql) return@transaction postgresDescribe(table)
            val meta = connection.metaData
            val keys = collect(meta.getPrimaryKeys(catalog, if (mysql) null else table.schema, table.name), 64) {
                it.getString("COLUMN_NAME") to it.getInt("KEY_SEQ")
            }.toMap()
            val columns = collect(meta.getColumns(catalog, if (mysql) null else pattern(table.schema), pattern(table.name), "%"), 1024) { row ->
                ColumnDef(row.getString("COLUMN_NAME"), row.getInt("DATA_TYPE"), row.getString("TYPE_NAME"),
                    row.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, row.getInt("COLUMN_SIZE"), row.getInt("DECIMAL_DIGITS"),
                    row.getString("IS_AUTOINCREMENT") == "YES", row.getString("IS_GENERATEDCOLUMN") == "YES",
                    keys[row.getString("COLUMN_NAME")] ?: 0, row.getString("COLUMN_DEF") != null)
            }
            if (columns.isEmpty()) throw WorkspaceRejected("没有可见列")
            TableSchema(table, columns, false, "${profile.kind.label} 当前提供只读浏览和查询")
        }
    }
    // pgjdbc metadata calls JDBC closeOnCompletion(), which Android API26/30 do not expose.
    // Query documented PostgreSQL catalogs with parameters and explicitly own all resources.
    private fun postgresDescribe(table: TableRef): TableSchema {
        val keys = metadata("""
            SELECT a.attname,array_position(i.indkey::smallint[],a.attnum)+1
            FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class t ON t.oid=i.indrelid
            JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid=t.oid AND a.attnum=ANY(i.indkey)
            WHERE i.indisprimary AND n.nspname=? AND t.relname=?
        """.trimIndent(), listOf(table.schema, table.name)) { rows -> collect(rows, 64) { it.getString(1) to it.getInt(2) }.toMap() }
        val columns = metadata("""
            SELECT column_name,udt_name,is_nullable,character_maximum_length,numeric_precision,numeric_scale,
                is_identity,is_generated,column_default
            FROM information_schema.columns WHERE table_catalog=current_database() AND table_schema=? AND table_name=?
            ORDER BY ordinal_position LIMIT 1025
        """.trimIndent(), listOf(table.schema, table.name)) { rows -> collect(rows, 1024) {
            val name = it.getString(1); val type = it.getString(2)
            val jdbc = when (type) {
                "int2" -> java.sql.Types.SMALLINT; "int4" -> java.sql.Types.INTEGER; "int8" -> java.sql.Types.BIGINT
                "numeric" -> java.sql.Types.NUMERIC; "bool" -> java.sql.Types.BOOLEAN
                "varchar", "text", "bpchar", "name" -> java.sql.Types.VARCHAR
                "bytea" -> java.sql.Types.VARBINARY; "date" -> java.sql.Types.DATE; "time" -> java.sql.Types.TIME
                "timestamp" -> java.sql.Types.TIMESTAMP; "timestamptz" -> java.sql.Types.TIMESTAMP_WITH_TIMEZONE
                "float4" -> java.sql.Types.REAL; "float8" -> java.sql.Types.DOUBLE
                else -> java.sql.Types.OTHER
            }
            val size = if (jdbc == java.sql.Types.NUMERIC) it.getInt(5) else it.getInt(4)
            ColumnDef(name, jdbc, type, it.getString(3) == "YES", size, it.getInt(6), it.getString(7) == "YES",
                it.getString(8) != "NEVER", keys[name] ?: 0, it.getString(9) != null)
        } }
        if (columns.isEmpty()) throw WorkspaceRejected("没有可见列")
        return TableSchema(table, columns, false, "PostgreSQL 当前提供只读浏览和查询")
    }
    private fun <T> metadata(sql: String, parameters: List<String> = emptyList(), block: (ResultSet) -> T): T {
        connection.prepareStatement(sql).use { statement ->
            statementChanged(statement)
            try {
                statement.queryTimeout = 10; statement.maxRows = 1025
                parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                return statement.executeQuery().use(block)
            } finally { statementChanged(null) }
        }
    }
    private fun quote(name: String): String {
        require(name.isNotEmpty() && '\u0000' !in name)
        val q = if (mysql) "`" else "\""
        return q + name.replace(q, q + q) + q
    }
    override fun page(table: TableRef, offset: Int, columnOffset: Int, view: TableView): ResultPage {
        require(offset in 0..1_000_000)
        val schema = describe(table)
        if (offset != 0 && schema.keys.isEmpty()) throw WorkspaceRejected("没有主键，仅显示首页")
        val columns = TableColumns.window(schema, columnOffset).joinToString(",") { quote(it.name) }
        val compiled = TableViews.compile(schema, view, ::quote)
        return query("SELECT $columns FROM ${quote(table.schema)}.${quote(table.name)}${compiled.where}${compiled.order} LIMIT 101 OFFSET $offset", offset, schema, compiled.parameters).copy(columnOffset = columnOffset, totalColumns = schema.columns.size, view = view)
    }
    override fun select(sql: String): ResultPage = query(ReadQueryPolicy.validate(sql, profile.kind), 0, null)
    private fun query(sql: String, offset: Int, schema: TableSchema?, parameters: List<Any> = emptyList()): ResultPage = transaction {
        connection.prepareStatement(sql).use { statement ->
            statementChanged(statement)
            try {
                statement.queryTimeout = 10
                statement.maxRows = 101
                statement.fetchSize = 100
                parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { JdbcResultReader.read(it, offset, schema) }
            } finally { statementChanged(null) }
        }
    }
    override fun isClosed() = connection.isClosed
    override fun close() { try { connection.close() } catch (_: Exception) {} }
}
