package com.example.datasail.core

/** Metadata failures remain visible without discarding successful, independent lists. */
data class SqlNavigation(val catalogs: List<String>, val schemas: List<String>, val schema: String?,
    val tables: List<TableRef>, val issues: List<String>)

object SqlNavigator {
    fun load(session: SqlWorkspaceSession, preferredSchema: String? = null): SqlNavigation {
        val issues = mutableListOf<String>()
        fun <T> read(label: String, fallback: T, operation: () -> T): T = try { operation() }
        catch (error: Exception) {
            val failure = Failures.classify(error)
            if (session.isClosed() || failure.kind in setOf(ErrorKind.NETWORK, ErrorKind.TIMEOUT, ErrorKind.CANCELLED, ErrorKind.UNSUPPORTED, ErrorKind.TLS)) throw error
            issues += "$label：" + if (error is WorkspaceRejected) error.explanation else failure.description
            fallback
        }
        // The authenticated current catalog remains usable even when enumeration is denied.
        val catalogs = (read("数据库列表加载失败", emptyList()) { session.catalogs() } + session.catalog).distinct()
        val schemas = read("Schema 列表加载失败", emptyList()) { session.schemas() }
        val preferred = listOfNotNull(preferredSchema, when (session.profile.kind) {
            DatabaseKind.SQL_SERVER -> "dbo"
            DatabaseKind.POSTGRESQL -> "public"
            DatabaseKind.SQLITE -> "main"
            else -> session.catalog
        })
        val schema = preferred.firstOrNull { it in schemas }
            ?: schemas.firstOrNull { it !in setOf("sys", "INFORMATION_SCHEMA", "information_schema", "pg_catalog") }
            ?: schemas.firstOrNull()
        val tables = if (schema == null) emptyList() else read("表 / 视图加载失败", emptyList()) { session.tables(schema) }
        return SqlNavigation(catalogs, schemas, schema, tables, issues)
    }
}
