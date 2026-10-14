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
        DiscoveryContract.verify(profile, password, "synthetic")
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
    fun writes() {
        HttpsSearchSession(profile(user = "writer")).use { session -> HttpsSearchSession(profile(user = "writer")).use { other ->
            session.login(); other.login(); session.indices(); other.indices()
            for (unsafe in listOf(".", "..", "../", "%2e%2e", "a/b", "a\\b")) {
                for (kind in MutationKind.entries.filter { it != MutationKind.DROP_TABLE }) {
                    assertThrows(WorkspaceRejected::class.java) {
                        session.previewRecord(RecordDraft(kind, "synthetic", unsafe, "{}", "{}"))
                    }
                }
            }
            assertEquals(listOf("synthetic"), session.indices())
            val id = "write-fixture-${java.util.UUID.randomUUID()}"
            val json = "{\"code\":\"$id\",\"number\":7}"
            fun read(s: HttpsSearchSession) = s.search("synthetic", "{\"term\":{\"code\":\"$id\"}}").hits.single()
            val insert = session.previewRecord(RecordDraft(MutationKind.INSERT, "synthetic", id, replacement = json))
            val created = session.confirmRecord(insert.token)
            assertEquals(created.message, MutationStatus.COMMITTED, created.status)
            assertThrows(WorkspaceRejected::class.java) { session.confirmRecord(insert.token) }
            val old = read(session)
            val stale = session.previewRecord(RecordDraft(MutationKind.UPDATE, "synthetic", id, old.json, json.replace(":7", ":9")))
            val fresh = read(other)
            val concurrent = other.previewRecord(RecordDraft(MutationKind.UPDATE, "synthetic", id, fresh.json, json.replace(":7", ":8")))
            assertEquals(MutationStatus.COMMITTED, other.confirmRecord(concurrent.token).status)
            assertEquals(MutationStatus.CONFLICT, session.confirmRecord(stale.token).status)
            val current = read(session)
            val remove = session.previewRecord(RecordDraft(MutationKind.DELETE, "synthetic", id, current.json))
            assertEquals(MutationStatus.COMMITTED, session.confirmRecord(remove.token).status)
            assertTrue(session.search("synthetic", "{\"term\":{\"code\":\"$id\"}}").hits.isEmpty())
        } }
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
        for (kind in listOf(MutationKind.INSERT, MutationKind.DELETE)) {
            fault("write-drop").use { session ->
                session.login(); session.indices()
                val hit = if (kind == MutationKind.DELETE) session.search("synthetic", "{\"match_all\":{}}").hits.single() else null
                val preview = session.previewRecord(RecordDraft(kind, "synthetic", "synthetic-id", hit?.json, "{\"name\":\"0007\"}"))
                assertEquals(MutationStatus.UNKNOWN, session.confirmRecord(preview.token).status)
                assertTrue(session.isClosed())
                assertThrows(WorkspaceRejected::class.java) { session.confirmRecord(preview.token) }
            }
            fault("verify-write-once").use { it.login() }
        }
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
