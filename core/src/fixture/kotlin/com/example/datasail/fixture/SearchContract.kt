package com.example.datasail.fixture

import com.example.datasail.core.*
import org.junit.Assert.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real-engine contract; test accounts only. No test control commands in the application. */
class SearchContract(private val profile: ConnectionProfile, private val password: String, private val faultPort: Int) {
    private fun profile(host: String = this.profile.host, user: String = this.profile.username,
        trust: TrustStoreSpec? = this.profile.trustStore, port: Int = this.profile.port, scope: String = this.profile.database) =
        ConnectionProfile(this.profile.kind, host, port, scope, user, trustStore = trust)
    private fun HttpsSearchSession.login(secret: String = password) = login(Credentials(secret.toCharArray()))
    fun reads() {
        HttpsSearchSession(profile).use { session ->
            session.login()
            assertEquals(listOf("synthetic"), session.indices())
            assertTrue(session.mapping("synthetic").contains("keyword"))
            val first = session.search("synthetic", "{\"match_all\":{}}")
            assertEquals(100, first.hits.size); assertTrue(first.hasMore)
            assertEquals(100, session.search("synthetic", first.query, 100).hits.size)
            val last = session.search("synthetic", first.query, 200)
            assertEquals(5, last.hits.size); assertFalse(last.hasMore)
            val exact = session.search("synthetic", "{\"term\":{\"code\":\"0007\"}}")
            assertEquals(1, exact.hits.size)
            val json = exact.hits.single().json!!
            assertTrue(json.contains("9007199254740993")); assertTrue(json.contains("1.2300")); assertTrue(json.contains("0007")); assertTrue(json.contains("null"))
            assertEquals(1, session.search("synthetic", "{\"range\":{\"number\":{\"gte\":204}}}").hits.size)
            assertThrows(WorkspaceRejected::class.java) { session.search("../_all", first.query) }
            assertThrows(WorkspaceRejected::class.java) { session.search("synthetic", "{\"script\":{\"source\":\"1\"}}") }
            assertThrows(WorkspaceRejected::class.java) { session.search("synthetic", first.query, 1000) }
            val large = session.search("synthetic", "{\"term\":{\"code\":\"0204\"}}")
            assertNull(large.hits.single().json); assertNotNull(large.hits.single().unavailable)
        }
    }
    fun identityAndAuth() {
        fun failure(p: ConnectionProfile, secret: String, expected: ErrorKind) {
            HttpsSearchSession(p).use { session ->
                val error = assertThrows(Exception::class.java) { session.login(secret) }
                assertEquals(expected, Failures.classify(error).kind); assertTrue(session.isClosed())
            }
        }
        failure(profile(host = "127.0.0.1"), password, ErrorKind.TLS)
        failure(profile(trust = null), password, ErrorKind.TLS)
        failure(profile, "invalid-fixture-password", ErrorKind.AUTHENTICATION)
        HttpsSearchSession(profile(user = "denied")).use { session ->
            session.login(); val error = assertThrows(Exception::class.java) { session.indices() }
            assertEquals(ErrorKind.PERMISSION, Failures.classify(error).kind)
        }
    }
    /** Adversarial local HTTPS transport fixture, separate from real-engine acceptance. */
    fun transportBoundsAndCancellation() {
        fun fault(mode: String) = HttpsSearchSession(profile(user = mode, port = faultPort))
        for (mode in listOf("redirect", "oversized", "duplicate", "malformed")) fault(mode).use { session ->
            assertThrows(Exception::class.java) { session.login() }; assertTrue(session.isClosed())
        }
        fault("slow").use { session ->
            val start = System.nanoTime()
            val error = assertThrows(Exception::class.java) { session.login() }
            assertEquals(ErrorKind.TIMEOUT, Failures.classify(error).kind)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) in 8000..17000)
            assertTrue(session.isClosed())
        }
        fault("slow").use { session ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val future = worker.submit { assertThrows(Exception::class.java) { session.login() } }
                Thread.sleep(1000)
                val start = System.nanoTime(); session.close(); future.get(3, TimeUnit.SECONDS)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000)
            } finally { worker.shutdownNow() }
        }
        fault("partial").use { session ->
            session.login(); session.indices()
            assertThrows(WorkspaceRejected::class.java) { session.search("synthetic", "{\"match_all\":{}}") }
        }
    }
}
