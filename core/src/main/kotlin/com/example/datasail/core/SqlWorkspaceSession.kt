package com.example.datasail.core

/** SQL capabilities only. Document, key-value and search engines use separate interfaces. */
interface SqlWorkspaceSession : AutoCloseable {
    val id: String
    val profile: ConnectionProfile
    val catalog: String
    val writable: Boolean
    fun catalogs(): List<String>
    fun schemas(): List<String>
    fun switchCatalog(name: String)
    fun tables(schema: String): List<TableRef>
    fun describe(table: TableRef): TableSchema
    fun page(table: TableRef, offset: Int = 0, columnOffset: Int = 0): ResultPage
    fun select(sql: String): ResultPage
    fun cancel() {}
    fun isClosed(): Boolean
    fun setWritable(enabled: Boolean) { if (enabled) throw WorkspaceRejected("此数据库当前提供只读浏览和查询") }
    fun preview(kind: MutationKind, rowIndex: Int?, inputs: Map<String, CellInput>): MutationPreview = throw WorkspaceRejected("此数据库尚未开放图形编辑")
    fun discardPreview() {}
    fun confirm(token: String): MutationResult = throw WorkspaceRejected("此数据库尚未开放图形编辑")
}
