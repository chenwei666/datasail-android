package com.example.datasail.core

import java.lang.reflect.Proxy
import java.sql.SQLException
import java.sql.SQLTimeoutException
import org.junit.Assert.*
import org.junit.Test

class SqlNavigationTest {
    private fun session(kind: DatabaseKind = DatabaseKind.SQL_SERVER, schemas: List<String> = listOf("dbo", "sys"),
        catalogsError: Exception? = null, schemasError: Exception? = null, tablesError: Exception? = null): SqlWorkspaceSession =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SqlWorkspaceSession::class.java)) { _, method, args ->
            when (method.name) {
                "getCatalog" -> "synthetic"
                "getProfile" -> ConnectionProfile(kind, "localhost", 1433, "synthetic", "fixture")
                "isClosed" -> false
                "catalogs" -> { catalogsError?.let { throw it }; listOf("synthetic", "second") }
                "schemas" -> { schemasError?.let { throw it }; schemas }
                "tables" -> { tablesError?.let { throw it }; listOf(TableRef("synthetic", args!![0] as String, "records", 1, false)) }
                else -> error("Unexpected navigation operation: ${method.name}")
            }
        } as SqlWorkspaceSession
    @Test fun connectionLoadsDefaultSchemaAndTables() {
        val result = SqlNavigator.load(session())
        assertEquals("dbo", result.schema); assertEquals("records", result.tables.single().name)
        assertTrue(result.issues.isEmpty())
    }
    @Test fun catalogPermissionFailureStillBrowsesAuthenticatedDatabase() {
        val result = SqlNavigator.load(session(catalogsError = SQLException("sensitive server detail", "42000")))
        assertEquals(listOf("synthetic"), result.catalogs); assertEquals(1, result.tables.size)
        assertEquals(1, result.issues.size); assertFalse(result.issues.single().contains("sensitive"))
    }
    @Test fun schemaFailurePreservesDatabaseSelector() {
        val result = SqlNavigator.load(session(schemasError = SQLException("denied", "42000")))
        assertEquals(listOf("synthetic", "second"), result.catalogs)
        assertNull(result.schema); assertTrue(result.tables.isEmpty()); assertEquals(1, result.issues.size)
    }
    @Test fun tableFailurePreservesSchemaAndAllowsRetry() {
        val result = SqlNavigator.load(session(tablesError = WorkspaceRejected("对象列表超过上限")))
        assertEquals("dbo", result.schema); assertEquals(2, result.schemas.size)
        assertTrue(result.tables.isEmpty()); assertEquals(1, result.issues.size)
    }
    @Test fun schemaSelectionAndEngineDefaultsAreRespected() {
        assertEquals("custom", SqlNavigator.load(session(schemas = listOf("dbo", "custom")), "custom").schema)
        assertEquals("public", SqlNavigator.load(session(DatabaseKind.POSTGRESQL, listOf("information_schema", "public"))).schema)
        assertEquals("custom", SqlNavigator.load(session(schemas = listOf("sys", "custom")), "missing").schema)
        assertNull(SqlNavigator.load(session(schemas = emptyList())).schema)
    }
    @Test fun timeoutIsNotReportedAsSuccessfulMetadataLoad() {
        val error = assertThrows(Exception::class.java) { SqlNavigator.load(session(catalogsError = SQLTimeoutException())) }
        assertEquals(ErrorKind.TIMEOUT, Failures.classify(error).kind)
    }
}
