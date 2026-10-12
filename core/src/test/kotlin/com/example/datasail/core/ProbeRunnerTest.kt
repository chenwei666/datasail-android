package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** JVM resource/concurrency tests. These are explicitly NOT Android or database integration tests. */
class ProbeRunnerTest {
    private val profile = ConnectionProfile(DatabaseKind.SQL_SERVER, "localhost", 1433, "synthetic", "test")
    private fun credentials() = Credentials("synthetic-only".toCharArray())

    @Test fun `success checks actual returned values and closes all resources`() {
        val db = FakeDb()
        ProbeRunner(db).use { runner ->
            val report = run(runner)
            assertEquals(ExecutionState.SUCCEEDED, report.state)
            assertTrue(report.checks.all { it.status == CheckStatus.PASSED })
            assertEquals(7, report.checks.size)
            assertTrue(db.resultClosed.get() > 0)
            assertTrue(db.statementClosed.get() > 0)
            assertTrue(db.connectionClosed.get() > 0)
            assertEquals(1, db.connects.get())
        }
    }
    @Test fun `type corruption must fail instead of reporting success`() {
        val db = FakeDb(wrongDecimal = true)
        ProbeRunner(db).use { runner ->
            val report = run(runner)
            assertEquals(ExecutionState.FAILED, report.state)
            assertEquals(CheckStatus.FAILED, report.checks.first { it.name == "精确小数" }.status)
        }
    }
    @Test fun `TLS connection failure never reports success or retries`() {
        val db = FakeDb(connectFailure = SQLException("sensitive", "08001", javax.net.ssl.SSLHandshakeException("untrusted")))
        ProbeRunner(db).use { runner ->
            val report = run(runner)
            assertEquals(ExecutionState.FAILED, report.state)
            assertEquals(ErrorKind.TLS, report.failure?.kind)
            assertTrue(report.checks.isEmpty())
            assertEquals(1, db.connects.get())
        }
    }
    @Test fun `cancel invokes driver cancel and close and reports unknown without retry`() {
        val db = FakeDb(block = true)
        val completed = CountDownLatch(1)
        val report = AtomicReference<ProbeReport>()
        ProbeRunner(db).use { runner ->
            assertTrue(runner.start(profile, credentials(), ProbeMode.BASIC, {}, { report.set(it); completed.countDown() }))
            assertTrue(db.executing.await(3, TimeUnit.SECONDS))
            val rejected = credentials()
            assertFalse(runner.start(profile, rejected, ProbeMode.BASIC, {}, {}))
            assertThrows(IllegalStateException::class.java) { rejected.usePassword { it } }
            runner.cancel()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(ExecutionState.UNKNOWN, report.get().state)
            assertTrue(db.cancelled.await(3, TimeUnit.SECONDS))
            assertTrue(db.connectionClosed.get() > 0)
            assertEquals(1, db.connects.get())
        }
    }
    @Test fun `close failure cannot masquerade as disconnected success`() {
        ProbeRunner(FakeDb(closeFailure = true)).use { runner ->
            val report = run(runner)
            assertEquals(ExecutionState.UNKNOWN, report.state)
            assertEquals(CheckStatus.UNKNOWN, report.checks.last().status)
        }
    }
    @Test fun `closed runner rejects new work and destroys supplied credentials`() {
        val runner = ProbeRunner(FakeDb())
        runner.close()
        val secret = credentials()
        assertFalse(runner.start(profile, secret, ProbeMode.BASIC, {}, {}))
        assertThrows(IllegalStateException::class.java) { secret.usePassword { it } }
    }
    @Test fun `late cancellation cannot overwrite a terminal result`() {
        val states = java.util.Collections.synchronizedList(mutableListOf<ExecutionState>())
        val done = CountDownLatch(1)
        ProbeRunner(FakeDb()).use { runner ->
            assertTrue(runner.start(profile, credentials(), ProbeMode.BASIC, { states.add(it) }, {
                states.add(it.state); done.countDown()
            }))
            assertTrue(done.await(3, TimeUnit.SECONDS))
            runner.cancel()
            assertEquals(ExecutionState.SUCCEEDED, states.last())
        }
    }
    private fun run(runner: ProbeRunner): ProbeReport {
        val done = CountDownLatch(1)
        val result = AtomicReference<ProbeReport>()
        assertTrue(runner.start(profile, credentials(), ProbeMode.BASIC, {}, { result.set(it); done.countDown() }))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        return result.get()
    }
    private class FakeDb(val wrongDecimal: Boolean = false, val block: Boolean = false,
        val connectFailure: SQLException? = null, val closeFailure: Boolean = false) : SqlConnector {
        override val capabilities = SqlCapabilities()
        val connects = AtomicInteger()
        val resultClosed = AtomicInteger()
        val statementClosed = AtomicInteger()
        val connectionClosed = AtomicInteger()
        val executing = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun connect(profile: ConnectionProfile, credentials: Credentials): Connection {
            connects.incrementAndGet()
            connectFailure?.let { throw it }
            return proxy(Connection::class.java) { method, _ -> when (method) {
                "createStatement" -> statement()
                "close" -> { connectionClosed.incrementAndGet(); release.countDown(); if (closeFailure) throw SQLException(); null }
                else -> null
            } }
        }
        private fun statement() = proxy(Statement::class.java) { method, _ -> when (method) {
            "executeQuery" -> {
                executing.countDown()
                if (block) { check(release.await(3, TimeUnit.SECONDS)); throw SQLException("cancelled", "HY008") }
                result()
            }
            "cancel" -> { cancelled.countDown(); release.countDown(); null }
            "close" -> { statementClosed.incrementAndGet(); null }
            else -> null
        } }
        private fun result() = proxy(ResultSet::class.java) { method, _ -> when (method) {
            "next" -> true
            "getLong" -> 1L
            "getString" -> "数舟 · Tiếng Việt · 00123"
            "getObject" -> null
            "getBigDecimal" -> if (wrongDecimal) BigDecimal.ZERO else BigDecimal("1234567890123456.12345678")
            "close" -> { resultClosed.incrementAndGet(); null }
            else -> null
        } }
        private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args) })
    }
}
