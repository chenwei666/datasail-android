package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceRunnerTest {
    private val profile = ConnectionProfile(DatabaseKind.SQL_SERVER, "localhost", 1433, "synthetic", "test")
    private fun secret() = Credentials("synthetic-only".toCharArray())
    @Test fun `connect owns credentials and closes them after login`() {
        val connector = FakeConnector(); val credentials = secret(); val done = CountDownLatch(1)
        WorkspaceRunner(connector).use { runner ->
            assertTrue(runner.connect(profile, credentials) { assertTrue(it.isSuccess); done.countDown() })
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { credentials.usePassword { it } }
            runner.disconnect()
            assertTrue(connector.closed.await(3, TimeUnit.SECONDS))
        }
    }
    @Test fun `disconnect during login disposes late connection and never delivers stale success`() {
        val connector = FakeConnector(blockLogin = true); val callbacks = AtomicInteger()
        WorkspaceRunner(connector).use { runner ->
            assertTrue(runner.connect(profile, secret()) { callbacks.incrementAndGet() })
            assertTrue(connector.entered.await(3, TimeUnit.SECONDS))
            val rejected = secret()
            assertFalse(runner.connect(profile, rejected) {})
            assertThrows(IllegalStateException::class.java) { rejected.usePassword { it } }
            runner.disconnect(); connector.release.countDown()
            assertTrue(connector.closed.await(3, TimeUnit.SECONDS))
            assertEquals(0, callbacks.get()); assertEquals(1, connector.connects.get())
        }
    }
    @Test fun `closed runner cannot retain credentials or create sessions`() {
        val connector = FakeConnector(); val runner = WorkspaceRunner(connector); runner.close()
        val credentials = secret()
        assertFalse(runner.connect(profile, credentials) {})
        assertThrows(IllegalStateException::class.java) { credentials.usePassword { it } }
        assertEquals(0, connector.connects.get())
    }
    @Test fun `successful operation that closes connection cannot report a live session`() {
        WorkspaceRunner(FakeConnector()).use { runner ->
            val ready = CountDownLatch(1)
            runner.connect(profile, secret()) { ready.countDown() }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            val done = CountDownLatch(1)
            val reply = java.util.concurrent.atomic.AtomicReference<SessionReply<Unit>>()
            assertTrue(runner.execute({ reply.set(it); done.countDown() }) { it.close() })
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(reply.get().result.isSuccess)
            assertEquals(SessionSnapshot(null, false, false), reply.get().session)
        }
    }
    @Test fun `repeated disconnect leaves room for reconnect behind unfinished cleanup`() {
        val connector = FakeConnector(blockFirstClose = true)
        WorkspaceRunner(connector).use { runner ->
            val first = CountDownLatch(1)
            assertTrue(runner.connect(profile, secret()) { first.countDown() })
            assertTrue(first.await(3, TimeUnit.SECONDS))
            runner.disconnect()
            try {
                assertTrue(connector.closeEntered.await(3, TimeUnit.SECONDS))
                runner.disconnect() // The UI also disconnects before opening a connection.
                val second = CountDownLatch(1)
                assertTrue(runner.connect(profile, secret()) { assertTrue(it.isSuccess); second.countDown() })
                assertEquals(1L, second.count) // Disposal must finish before the next login.
                connector.releaseClose.countDown()
                assertTrue(second.await(3, TimeUnit.SECONDS))
                assertEquals(2, connector.connects.get())
                val checked = CountDownLatch(1)
                assertTrue(runner.execute({ assertTrue(it.session.connected); checked.countDown() }) { it.catalog })
                assertTrue(checked.await(3, TimeUnit.SECONDS))
            } finally { connector.releaseClose.countDown() }
        }
    }
    private class FakeConnector(val blockLogin: Boolean = false, val blockFirstClose: Boolean = false) : SqlConnector {
        override val capabilities = SqlCapabilities()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1); val connects = AtomicInteger()
        val closeEntered = CountDownLatch(2); val releaseClose = CountDownLatch(1)
        override fun connect(profile: ConnectionProfile, credentials: Credentials): Connection {
            val number = connects.incrementAndGet(); entered.countDown()
            val connectionClosed = java.util.concurrent.atomic.AtomicBoolean()
            if (blockLogin) check(release.await(3, TimeUnit.SECONDS))
            val statement = Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(Statement::class.java)) { _, method, _ ->
                when (method.name) { "execute" -> false; else -> null }
            } as Statement
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
                when (method.name) {
                    "createStatement" -> statement
                    "isClosed" -> connectionClosed.get()
                    "close" -> {
                        if (blockFirstClose && number == 1) { closeEntered.countDown(); check(releaseClose.await(3, TimeUnit.SECONDS)) }
                        connectionClosed.set(true); closed.countDown(); null
                    }
                    else -> null
                }
            } as Connection
        }
    }
}
