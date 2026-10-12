package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.sql.Types

class MultiDatabaseTest {
    @Test fun cleanupFailureCannotMaskQueryTimeout() {
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        fun statement(): java.sql.PreparedStatement = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, method, _ ->
            when (method.name) {
                "execute" -> true
                "executeQuery" -> throw java.sql.SQLTimeoutException("synthetic timeout", "HYT00")
                else -> null
            }
        } as java.sql.PreparedStatement
        val connection = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "createStatement", "prepareStatement" -> statement()
                "isClosed" -> closed.get()
                "close" -> { closed.set(true); null }
                "rollback" -> throw java.sql.SQLException("synthetic closed connection", "08003")
                else -> null
            }
        } as java.sql.Connection
        JdbcBrowseSession(connection, ConnectionProfile(DatabaseKind.MYSQL, "localhost", 3306, "synthetic", "u")).use { session ->
            val error = assertThrows(java.sql.SQLTimeoutException::class.java) { session.select("SELECT 1") }
            assertEquals(ErrorKind.TIMEOUT, Failures.classify(error).kind)
            assertEquals(1, error.suppressed.size)
            assertTrue(closed.get())
        }
    }

    @Test fun postgresLoginTimeoutIsNotMisreportedAsNetworkFailure() {
        assertEquals(ErrorKind.TIMEOUT, Failures.classify(java.sql.SQLException("Connection attempt timed out.", "08001")).kind)
        assertEquals(ErrorKind.NETWORK, Failures.classify(java.sql.SQLException("another failure", "08001")).kind)
    }
    @Test fun dialectGateRejectsExecutableCommentsAndAmbiguousQuotes() {
        for (kind in listOf(DatabaseKind.MYSQL, DatabaseKind.MARIADB, DatabaseKind.POSTGRESQL, DatabaseKind.SQLITE)) {
            for (sql in listOf("SELECT 1 /*! INTO OUTFILE 'x' */", "SELECT 1 /*M! INTO OUTFILE 'x' */", "SELECT 'a\\' INTO OUTFILE 'x'", "SELECT ${'$'}${'$'}bad${'$'}${'$'}"))
                assertThrows(WorkspaceRejected::class.java) { ReadQueryPolicy.validate(sql, kind) }
        }
        assertEquals("SELECT `a``b` FROM `t`", ReadQueryPolicy.validate("SELECT `a``b` FROM `t`", DatabaseKind.MYSQL))
        assertThrows(WorkspaceRejected::class.java) { ReadQueryPolicy.validate("SELECT 1--x INTO OUTFILE 'x'", DatabaseKind.MYSQL) }
    }
    @Test fun timestampsAreNotSqlServerRowversionsAcrossEngines() {
        assertFalse(ColumnDef("t", Types.TIMESTAMP, "timestamp", true, 0, 0, false, false).rowVersion)
        assertTrue(ColumnDef("v", Types.BINARY, "timestamp", false, 8, 0, false, false).rowVersion)
    }
    @Test fun postgresRequiresStrictTlsWithoutReconnectOrPasswordPersistence() {
        val properties = JdbcConnector.safeProperties(ConnectionProfile(DatabaseKind.POSTGRESQL, "db.example.test", 5432, "synthetic", "u"))
        assertEquals("verify-full", properties.getProperty("sslmode"))
        assertEquals(PostgreSqlTlsFactory::class.java.name, properties.getProperty("sslfactory"))
        assertEquals("always", properties.getProperty("readOnlyMode"))
        assertFalse(properties.containsKey("password"))
    }
}
