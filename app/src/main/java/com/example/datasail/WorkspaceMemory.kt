package com.example.datasail

import com.example.datasail.core.*

/** Bounded, process-local navigation only. No result rows, passwords or write previews. */
data class TableLocation(val table: TableRef, val offset: Int, val columnOffset: Int, val view: TableView)
data class GridOptions(val width: Int? = null, val wrap: Boolean? = null, val zoom: Float = 1f, val hidden: Set<String> = emptySet())
data class QueryRun(val sql: String, val catalog: String, val schema: String, val rows: Int, val elapsedMs: Long)
internal class WorkspaceMemory {
    private val drafts = linkedMapOf<String, String>()
    private val locations = linkedMapOf<Pair<String, TableRef>, TableLocation>()
    private val last = linkedMapOf<String, TableLocation>()
    private val grids = linkedMapOf<Pair<String, TableRef>, GridOptions>()
    private val history = linkedMapOf<String, List<QueryRun>>()
    private fun <K, V> MutableMap<K, V>.boundedPut(key: K, value: V, limit: Int) {
        remove(key); put(key, value); while (size > limit) remove(keys.first())
    }
    fun draft(key: String) = drafts[key] ?: "SELECT 1 AS 示例"
    fun draft(key: String, sql: String) { if (key.isNotEmpty() && sql.length <= 16000) drafts.boundedPut(key, sql, 20) }
    fun remember(key: String, page: ResultPage) { page.tableSchema?.table?.let { table ->
        val location = TableLocation(table, page.offset, page.columnOffset, page.view)
        locations.boundedPut(key to table, location, 50); last.boundedPut(key, location, 20)
    } }
    fun location(key: String, table: TableRef) = locations[key to table]
    fun forgetView(key: String, table: TableRef) {
        locations.remove(key to table)
        if (last[key]?.table == table) last.remove(key)
    }
    fun last(key: String) = last[key]
    fun grid(key: String, table: TableRef) = grids[key to table] ?: GridOptions()
    fun grid(key: String, table: TableRef, options: GridOptions) { grids.boundedPut(key to table, options, 50) }
    fun history(key: String) = history[key].orEmpty()
    fun record(key: String, value: QueryRun) { history.boundedPut(key, (listOf(value) + history(key)).take(30), 20) }
    fun clearHistory() { history.clear() }
    fun clear() { drafts.clear(); locations.clear(); last.clear(); grids.clear(); history.clear() }
}

internal fun WorkspaceUiState.afterFailure(error: Throwable): WorkspaceUiState {
    val safe = Failures.classify(error)
    val lost = !connected || safe.kind in setOf(ErrorKind.TIMEOUT, ErrorKind.NETWORK, ErrorKind.CANCELLED, ErrorKind.UNSUPPORTED)
    return copy(busy = false, preview = null, result = if (lost) null else result,
        resultStale = !lost && result != null, selectedSchema = if (lost) null else selectedSchema,
        documents = if (lost) null else documents, searchPage = if (lost) null else searchPage,
        mapping = if (lost) null else mapping, keys = if (lost) null else keys, keyContent = if (lost) null else keyContent,
        connected = connected && !lost, writable = writable && !lost,
        message = if (error is WorkspaceRejected) error.explanation else if (safe.kind == ErrorKind.TLS)
            "TLS 校验失败。请在连接页获取并核对服务器证书，或导入 CA。" else safe.description)
}
