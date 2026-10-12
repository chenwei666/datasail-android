package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** These exercise a real server on the host JVM, NOT an Android device. */
class DatabaseIntegrationTest {
    private val config = Properties().apply { File(System.getProperty("datasail.test.config")).inputStream().use(::load) }
    private val kind = DatabaseKind.valueOf(config.getProperty("kind"))
    private fun profile(host: String = config.getProperty("host")) = ConnectionProfile(kind, host,
        config.getProperty("port").toInt(), config.getProperty("database"), config.getProperty("username"))
    private fun credentials() = Credentials(config.getProperty("password").toCharArray())

    @Test fun `real server synthetic values round trip and connection closes`() {
        val result = probe(ProbeMode.BASIC)
        assertEquals(result.toString(), ExecutionState.SUCCEEDED, result.state)
        assertEquals(7, result.checks.count { it.status == CheckStatus.PASSED })
    }
    @Test fun `real driver enforces query timeout`() {
        val result = probe(ProbeMode.TIMEOUT)
        assertEquals(result.toString(), ExecutionState.SUCCEEDED, result.state)
        assertTrue(result.checks.any { it.name == "查询超时" && it.status == CheckStatus.PASSED })
    }
    @Test fun `real cancellation stays unknown instead of claiming server stopped`() {
        val result = probe(ProbeMode.CANCEL)
        assertEquals(ExecutionState.UNKNOWN, result.state)
        assertTrue(result.checks.any { it.name == "取消" && it.status == CheckStatus.UNKNOWN })
    }
    @Test fun `server confirms encrypted session`() {
        credentials().use { secret ->
            JdbcConnector().connect(profile(), secret).use { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 3
                    val sql = if (kind == DatabaseKind.MYSQL) "SHOW SESSION STATUS LIKE 'Ssl_cipher'"
                    else "SELECT encrypt_option FROM sys.dm_exec_connections WHERE session_id = @@SPID"
                    statement.executeQuery(sql).use { rows ->
                        assertTrue(rows.next())
                        if (kind == DatabaseKind.MYSQL) assertTrue(rows.getString(2).isNotBlank())
                        else assertEquals("TRUE", rows.getString(1))
                    }
                }
            }
        }
    }
    @Test fun `certificate hostname mismatch is rejected`() {
        credentials().use { secret ->
            val exception = assertThrows(SQLException::class.java) {
                JdbcConnector().connect(profile("127.0.0.1"), secret).use { fail("Wrong certificate identity accepted") }
            }
            assertEquals(ErrorKind.TLS, Failures.classify(exception).kind)
        }
    }
    @Test fun `invalid credentials fail authentication`() {
        Credentials("deliberately-wrong-synthetic-password".toCharArray()).use { secret ->
            val exception = assertThrows(SQLException::class.java) { JdbcConnector().connect(profile(), secret).use { fail() } }
            assertEquals(ErrorKind.AUTHENTICATION, Failures.classify(exception).kind)
        }
    }
    private fun probe(mode: ProbeMode): ProbeReport {
        val done = CountDownLatch(1)
        val result = AtomicReference<ProbeReport>()
        ProbeRunner().use { runner ->
            assertTrue(runner.start(profile(), credentials(), mode, {}, { result.set(it); done.countDown() }))
            assertTrue("Probe exceeded bounded socket/query budget", done.await(25, TimeUnit.SECONDS))
        }
        return result.get()
    }
}
