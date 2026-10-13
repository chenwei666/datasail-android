package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DatabaseDiscoveryTest {
    private val profile = ConnectionProfile(DatabaseKind.MYSQL, "localhost", 3306, "unused", "reader")
    @Test fun bootstrapAndBoundedResultsAreHonest() {
        assertEquals("postgres", DiscoveryDefaults.database(DatabaseKind.POSTGRESQL))
        assertEquals("business", DiscoveryDefaults.database(DatabaseKind.POSTGRESQL, " business "))
        assertEquals("*", DiscoveryDefaults.database(DatabaseKind.OPENSEARCH))
        assertThrows(WorkspaceRejected::class.java) { DiscoveryDefaults.database(DatabaseKind.SQLITE) }
        val result = DiscoveredDatabases.bounded((0..200).map { "database_$it" })
        assertTrue(result.limited); assertEquals(200, result.names.size)
        assertFalse(DiscoveredDatabases.bounded(emptyList()).limited)
        assertThrows(IllegalArgumentException::class.java) { DiscoveredDatabases.bounded(listOf("db\nspoof")) }
    }
    @Test fun cancellationClosesTransportDiscardsLateCallbackAndCredentials() {
        val started = CountDownLatch(1); val closed = CountDownLatch(1); val finished = CountDownLatch(1)
        val callback = AtomicBoolean(false)
        val secret = Credentials("synthetic-only".toCharArray())
        val task = object : DiscoveryTask {
            override fun scan(profile: ConnectionProfile, credentials: Credentials): DiscoveredDatabases {
                started.countDown()
                check(closed.await(5, TimeUnit.SECONDS))
                finished.countDown()
                return DiscoveredDatabases(listOf("stale"))
            }
            override fun close() { closed.countDown() }
        }
        DiscoveryRunner { task }.use { runner ->
            assertTrue(runner.scan(profile, secret) { callback.set(true) })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val rejected = Credentials("synthetic-rejected".toCharArray())
            assertFalse(runner.scan(profile, rejected) { fail("Busy scan must not be queued") })
            assertThrows(IllegalStateException::class.java) { rejected.usePassword { } }
            runner.cancel()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            // Wait for the worker to dispose its credentials and become available without sleeping.
            val next = CountDownLatch(1)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!runner.scan(profile, Credentials(charArrayOf('x'))) { next.countDown() }) {
                check(System.nanoTime() < deadline); Thread.yield()
            }
            assertTrue(next.await(5, TimeUnit.SECONDS))
            assertFalse(callback.get())
            assertThrows(IllegalStateException::class.java) { secret.usePassword { } }
        }
    }
    @Test fun taskConstructionFailureAlsoDisposesCredentials() {
        val secret = Credentials(charArrayOf('x')); val done = CountDownLatch(1)
        DiscoveryRunner { throw IllegalStateException("synthetic") }.use { runner ->
            assertTrue(runner.scan(profile, secret) { assertTrue(it.isFailure); done.countDown() })
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { secret.usePassword { } }
        }
    }
}
