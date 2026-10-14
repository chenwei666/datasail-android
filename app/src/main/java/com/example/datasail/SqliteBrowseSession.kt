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

/** Edits only the imported private copy. Never changes the original document or another app database. */
class SqliteBrowseSession(file: File, override val profile: ConnectionProfile) : SqlWorkspaceSession {
    private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val signal = AtomicReference<CancellationSignal?>()
    override val id = UUID.randomUUID().toString()
    override val catalog = "main"
    override var writable = false; private set
    private var currentPage: ResultPage? = null
    private var pending: JdbcWritePlan? = null
    init { db.setForeignKeyConstraintsEnabled(true); db.execSQL("PRAGMA query_only=ON") }
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
                columns += ColumnDef(cursor.getString(1), affinity(cursor.getString(2)), cursor.getString(2), cursor.getInt(3) == 0,
                    Int.MAX_VALUE, 0, false, false, cursor.getInt(5), !cursor.isNull(4))
            }
            if (columns.isEmpty()) throw WorkspaceRejected("没有可见列")
            val ddl = query("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table.name)) { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
            val triggers = query("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND tbl_name=?", arrayOf(table.name)) { it.moveToFirst(); it.getInt(0) > 0 }
            val version = query("PRAGMA schema_version") { it.moveToFirst(); it.getLong(0).toString() }
            val singleIntegerKey = columns.singleOrNull { it.primaryKeyOrder > 0 }?.takeIf { it.typeName.equals("INTEGER", true) && !ddl.contains("WITHOUT ROWID", true) }
            val typedColumns = columns.map { if (it == singleIntegerKey) it.copy(identity = true, nullable = false) else it }
            TableSchema(table, typedColumns, triggers, if (Regex("(?i)\\b(VIRTUAL|GENERATED)\\b").containsMatchIn(ddl)) "虚拟表或生成列暂不支持编辑" else null, "$version:$ddl")
        }
    }
    override fun page(table: TableRef, offset: Int, columnOffset: Int, view: TableView): ResultPage {
        pending = null; currentPage = null
        require(offset in 0..1_000_000)
        val schema = describe(table)
        if (schema.keys.isEmpty() && offset != 0) throw WorkspaceRejected("没有主键，仅显示首页")
        val compiled = TableViews.compile(schema, view, ::quote, sqlite = true)
        val sql = "SELECT ${TableColumns.window(schema, columnOffset).joinToString(",") { quote(it.name) }} FROM main.${quote(table.name)}${compiled.where}${compiled.order} LIMIT 101 OFFSET $offset"
        return query(sql, compiled.parameters.map { it.toString() }.toTypedArray()) { read(it, offset, schema) }.copy(columnOffset = columnOffset, totalColumns = schema.columns.size, view = view).also { currentPage = it }
    }
    override fun select(sql: String): ResultPage { pending = null; currentPage = null; return query(ReadQueryPolicy.validate(sql, DatabaseKind.SQLITE)) { read(it, 0, null) } }
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
                    Cursor.FIELD_TYPE_FLOAT -> DbValue.Approximate(cursor.getDouble(index).toString())
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).let {
                        if (it.size > 256) DbValue.Unavailable("二进制超过 256 字节") else DbValue.Binary(it)
                    }
                    else -> cursor.getString(index).let {
                        val limit = minOf(4000, budget.coerceAtLeast(0)); budget -= minOf(it.length, limit + 1)
                        if (it.length > limit) DbValue.Unavailable("长值超出读取预算") else DbValue.Text(it)
                    }
                }
            }
        }
        return ResultPage(columns, rows, more, offset, schema, schema?.keys?.isNotEmpty() == true, totalColumns = cursor.columnCount)
    }
    private fun affinity(type: String): Int {
        val name = type.uppercase(java.util.Locale.ROOT)
        return when {
            "INT" in name -> Types.BIGINT
            "CHAR" in name || "TEXT" in name || "CLOB" in name -> Types.VARCHAR
            "REAL" in name || "FLOA" in name || "DOUB" in name -> Types.DOUBLE
            else -> Types.OTHER // Dynamic/NUMERIC/BLOB columns need a dedicated typed editor.
        }
    }
    override fun setWritable(enabled: Boolean) { pending = null; writable = enabled }
    override fun discardPreview() { pending = null }
    override fun preview(kind: MutationKind, rowIndex: Int?, inputs: Map<String, CellInput>): MutationPreview {
        pending = null
        if (!writable) throw WorkspaceRejected("请先开启本次编辑")
        return JdbcWrites.prepare(currentPage ?: throw WorkspaceRejected("请先浏览表"), kind, rowIndex, inputs, false, profile, sqlite = true).also { pending = it }.preview
    }
    // Track the exact commit boundary to distinguish rollback from an unknown commit result.
    @android.annotation.SuppressLint("UseKtx")
    override fun confirm(token: String): MutationResult {
        val plan = pending ?: throw WorkspaceRejected("确认已失效")
        pending = null
        if (!writable || token != plan.preview.token || System.currentTimeMillis() > plan.preview.expiresAtMillis) throw WorkspaceRejected("确认已失效")
        var committing = false
        try {
            db.execSQL("PRAGMA query_only=OFF")
            db.beginTransaction()
            if (describe(plan.schema.table) != plan.schema) throw WorkspaceRejected("表结构或触发器已变化")
            val affected = db.compileStatement(plan.preview.sql).use { statement ->
                plan.parameters.forEachIndexed { index, value -> when (value) {
                    DbValue.Null -> statement.bindNull(index + 1)
                    is DbValue.Integer -> statement.bindLong(index + 1, value.value)
                    is DbValue.Text -> statement.bindString(index + 1, value.value)
                    is DbValue.Approximate -> statement.bindDouble(index + 1, value.value.toDouble())
                    else -> throw WorkspaceRejected("SQLite 字段不能无损绑定")
                } }
                statement.executeUpdateDelete()
            }
            if (affected != 1) return MutationResult(MutationStatus.CONFLICT, "原行已变化，修改已回滚")
            db.setTransactionSuccessful(); committing = true
            db.endTransaction()
            return MutationResult(MutationStatus.COMMITTED, "已修改应用内 SQLite 副本；原始文件未更改")
        } catch (e: Exception) {
            if (committing) runCatching { close() }
            return MutationResult(if (committing) MutationStatus.UNKNOWN else MutationStatus.ROLLED_BACK, if (committing) "提交结果未知，请重新打开副本核对" else "修改失败，副本事务已回滚")
        } finally {
            currentPage = null
            try {
                if (db.isOpen && db.inTransaction()) db.endTransaction()
                if (db.isOpen) db.execSQL("PRAGMA query_only=ON")
            } catch (_: Exception) { runCatching { close() } }
        }
    }
    override fun cancel() { signal.get()?.cancel() }
    override fun isClosed() = !db.isOpen
    override fun close() { cancel(); timer.shutdownNow(); db.close() }
}
