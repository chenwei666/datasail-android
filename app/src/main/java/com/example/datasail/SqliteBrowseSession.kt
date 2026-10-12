package com.example.datasail

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.example.datasail.core.*
import java.io.File
import java.sql.Types
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owns a private, read-only imported copy. No writes or access to another application's active DB. */
class SqliteBrowseSession(file: File, override val profile: ConnectionProfile) : SqlWorkspaceSession {
    private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val signal = AtomicReference<CancellationSignal?>()
    override val id = UUID.randomUUID().toString()
    override val catalog = "main"
    override val writable = false
    override fun catalogs() = listOf("main")
    override fun schemas() = listOf("main")
    override fun switchCatalog(name: String) { if (name != "main") throw WorkspaceRejected("SQLite 副本仅开放 main") }
    private fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
    private fun <T> query(sql: String, args: Array<String>? = null, block: (Cursor) -> T): T {
        val cancellation = CancellationSignal()
        signal.set(cancellation)
        val timeout = timer.schedule({ cancellation.cancel() }, 10, TimeUnit.SECONDS)
        try { return db.rawQuery(sql, args, cancellation).use(block) }
        catch (_: android.os.OperationCanceledException) { throw java.sql.SQLTimeoutException("SQLite operation cancelled", "HYT00") }
        finally { timeout.cancel(false); signal.compareAndSet(cancellation, null) }
    }
    override fun tables(schema: String): List<TableRef> {
        if (schema != "main") throw WorkspaceRejected("Schema 不属于当前副本")
        return query("SELECT name,type FROM sqlite_master WHERE type IN ('table','view') AND name NOT GLOB 'sqlite_*' ORDER BY name LIMIT 1001") {
            val tables = mutableListOf<TableRef>()
            while (it.moveToNext()) {
                if (tables.size == 1000) throw WorkspaceRejected("对象超过 1000 项")
                tables += TableRef("main", "main", it.getString(0), 0, it.getString(1) == "view")
            }
            tables
        }
    }
    override fun describe(table: TableRef): TableSchema {
        if (table !in tables("main")) throw WorkspaceRejected("对象不存在，请刷新")
        return query("PRAGMA main.table_info(${quote(table.name)})") { cursor ->
            val columns = mutableListOf<ColumnDef>()
            while (cursor.moveToNext()) {
                if (columns.size == 1024) throw WorkspaceRejected("列数超限")
                columns += ColumnDef(cursor.getString(1), Types.OTHER, cursor.getString(2), cursor.getInt(3) == 0,
                    0, 0, false, false, cursor.getInt(5), !cursor.isNull(4))
            }
            if (columns.isEmpty()) throw WorkspaceRejected("没有可见列")
            TableSchema(table, columns, false, "SQLite 导入副本只读；本版不写回源文件")
        }
    }
    override fun page(table: TableRef, offset: Int): ResultPage {
        require(offset in 0..1_000_000)
        val schema = describe(table)
        if (schema.keys.isEmpty() && offset != 0) throw WorkspaceRejected("没有主键，仅显示首页")
        val order = if (schema.keys.isEmpty()) "" else " ORDER BY " + schema.keys.joinToString(",") { quote(it.name) }
        val sql = "SELECT ${schema.columns.take(64).joinToString(",") { quote(it.name) }} FROM main.${quote(table.name)}$order LIMIT 101 OFFSET $offset"
        return query(sql) { read(it, offset, schema) }
    }
    override fun select(sql: String): ResultPage = query(ReadQueryPolicy.validate(sql, DatabaseKind.SQLITE)) { read(it, 0, null) }
    private fun read(cursor: Cursor, offset: Int, schema: TableSchema?): ResultPage {
        val columns = cursor.columnNames.take(64).map { ResultColumn(it, "SQLite 动态类型", Types.OTHER) }
        val rows = mutableListOf<List<DbValue>>()
        var budget = 262144
        var more = false
        while (cursor.moveToNext()) {
            if (rows.size == 100 || budget <= 0) { more = true; break }
            rows += columns.indices.map { index ->
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> DbValue.Null
                    Cursor.FIELD_TYPE_INTEGER -> DbValue.Integer(cursor.getLong(index))
                    Cursor.FIELD_TYPE_FLOAT -> DbValue.Approximate(cursor.getString(index))
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).let {
                        if (it.size > 256) DbValue.Unavailable("二进制超过 256 字节") else DbValue.Binary(it)
                    }
                    else -> cursor.getString(index).let {
                        val limit = minOf(2048, budget.coerceAtLeast(0)); budget -= minOf(it.length, limit + 1)
                        if (it.length > limit) DbValue.Unavailable("长值超出读取预算") else DbValue.Text(it)
                    }
                }
            }
        }
        return ResultPage(columns, rows, more, offset, schema, schema?.keys?.isNotEmpty() == true)
    }
    override fun cancel() { signal.get()?.cancel() }
    override fun isClosed() = !db.isOpen
    override fun close() { cancel(); timer.shutdownNow(); db.close() }
}
