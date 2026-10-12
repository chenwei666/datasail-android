package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.sql.SQLException
import java.sql.SQLTimeoutException
import javax.net.ssl.SSLHandshakeException

class SafetyTest {
    private fun profile(kind: DatabaseKind = DatabaseKind.SQL_SERVER, host: String = "db.example.test") =
        ConnectionProfile(kind, host, kind.defaultPort, "synthetic", "test-user")

    @Test fun `connection strings cannot inject options through host or database`() {
        listOf("localhost;trustServerCertificate=true", "localhost?sslMode=disable", "a/b", "a@b", "a\npassword=x").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { profile(host = host) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfile(DatabaseKind.MYSQL, "localhost", 3306, "db?sslMode=disable", "u")
        }
    }
    @Test fun `both drivers require verified TLS and disallow risky options`() {
        val ms = JdbcConnector.safeProperties(profile())
        assertEquals("true", ms.getProperty("encrypt"))
        assertEquals("false", ms.getProperty("trustServerCertificate"))
        assertEquals("0", ms.getProperty("connectRetryCount"))
        assertEquals("SqlPassword", ms.getProperty("authentication"))
        val my = JdbcConnector.safeProperties(profile(DatabaseKind.MYSQL))
        assertEquals("verify-full", my.getProperty("sslMode"))
        assertEquals("DATASAIL_VERIFIED", my.getProperty("tlsSocketType"))
        assertEquals("false", my.getProperty("allowLocalInfile"))
        assertEquals("false", my.getProperty("allowMultiQueries"))
        assertEquals("false", my.getProperty("allowPublicKeyRetrieval"))
        assertFalse(ms.containsKey("password"))
        assertFalse(JdbcConnector.url(profile()).contains("test-user"))
    }
    @Test fun `credentials are copied closed and redacted`() {
        val chars = "synthetic-only".toCharArray()
        val credentials = Credentials(chars)
        chars.fill('x')
        credentials.usePassword { assertEquals("synthetic-only", it) }
        assertFalse(credentials.toString().contains("synthetic-only"))
        credentials.close()
        assertThrows(IllegalStateException::class.java) { credentials.usePassword { it } }
        assertFalse(profile().toString().contains("db.example.test"))
    }
    @Test fun `nested certificate errors take precedence and exception text is never logged`() {
        val raw = SQLException("password=never-log jdbc:sqlserver://private-host", "08001", 9,
            SSLHandshakeException("private-host certificate"))
        val safe = Failures.classify(raw)
        assertEquals(ErrorKind.TLS, safe.kind)
        val output = SafeLog.event(ExecutionState.FAILED, safe) + safe.description
        listOf("never-log", "private-host", "password=", "jdbc:").forEach { assertFalse(output.contains(it)) }
    }
    @Test fun `error classes remain distinct`() {
        assertEquals(ErrorKind.TIMEOUT, Failures.classify(SQLTimeoutException()).kind)
        assertEquals(ErrorKind.AUTHENTICATION, Failures.classify(SQLException("", "28000")).kind)
        assertEquals(ErrorKind.PERMISSION, Failures.classify(SQLException("", "42000", 1142)).kind)
        assertEquals(ErrorKind.UNSUPPORTED, Failures.classify(NoClassDefFoundError()).kind)
        assertEquals(ErrorKind.CANCELLED, Failures.classify(SQLException("", "HY008")).kind)
        assertNull(Failures.classify(SQLException("", "secret-state")).sqlState)
    }
    @Test fun `MariaDB hostname mismatch without nested cause is safely classified`() {
        val error = SQLException("SSL hostname verification failed : private-host", "08006")
        assertEquals(ErrorKind.TLS, Failures.classify(error).kind)
        assertFalse(Failures.classify(error).toString().contains("private-host"))
    }
    @Test fun `driver raw log output is disabled before connection`() {
        JdbcConnector()
        assertEquals("true", System.getProperty("mariadb.logging.disable"))
        assertEquals(java.util.logging.Level.OFF, java.util.logging.Logger.getLogger("com.microsoft.sqlserver.jdbc").level)
    }
    @Test fun `values preserve null empty string leading zero decimal and binary ownership`() {
        assertNotEquals(DbValue.Null, DbValue.Text(""))
        assertEquals("00123", DbValue.Text("00123").value)
        assertEquals(BigDecimal("1234567890123456.12345678"), DbValue.Decimal(BigDecimal("1234567890123456.12345678")).value)
        assertEquals(Long.MAX_VALUE, DbValue.Integer(Long.MAX_VALUE).value)
        val bytes = byteArrayOf(1, 2)
        val value = DbValue.Binary(bytes)
        bytes[0] = 9
        value.copyBytes()[0] = 8
        assertArrayEquals(byteArrayOf(1, 2), value.copyBytes())
    }
}
