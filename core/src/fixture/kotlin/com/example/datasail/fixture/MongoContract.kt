package com.example.datasail.fixture

import com.example.datasail.core.*
import com.mongodb.*
import com.mongodb.client.MongoClients
import org.bson.BsonDocument
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext

/** Same real-server assertions on JVM and Android. Admin/failpoints exist only in test sources. */
class MongoContract(private val profile: ConnectionProfile, private val password: String) {
    private fun connect(p: ConnectionProfile = profile, pass: String = password): MongoSession {
        val session = MongoSession(p)
        try { session.login(Credentials(pass.toCharArray())); return session }
        catch (error: Exception) { session.close(); throw error }
    }
    fun reads() = connect().use { session ->
        DiscoveryContract.verify(profile, password)
        assertEquals(setOf("documents", "oversized"), session.collections().toSet())
        val pages = mutableListOf<DocumentPage>(); pages += session.find("documents", "{}")
        // Reading a page must not spend the next page's client timeout while the user inspects it.
        Thread.sleep(11000)
        while (pages.last().hasMore) { assertTrue(pages.size < 4); pages += session.next() }
        assertEquals(listOf(100, 100, 5), pages.map { it.documents.size })
        assertEquals(listOf(1, 2, 3), pages.map { it.page })
        val texts = pages.flatMap { it.documents }.map { it.json!! }
        assertEquals(205, texts.toSet().size)
        val selected = session.find("documents", "{\"name\":\"0007\"}")
        assertEquals(1, selected.documents.size); assertFalse(selected.hasMore)
        val json = selected.documents.single().json!!
        for (part in listOf("\$numberLong", "9007199254740993", "\$numberDecimal", "1.2300", "\$oid", "\$date", "\$binary", "null", "0007")) assertTrue(part, json.contains(part))
        assertEquals(1, session.find("documents", "{\"_id\":{\"\$oid\":\"0123456789abcdef01234567\"}}").documents.size)
        assertTrue(session.find("documents", "{\"name\":\"missing\"}").documents.isEmpty())
        assertNotNull(session.find("oversized", "{}").documents.single().unavailable)
        assertThrows(WorkspaceRejected::class.java) { session.find("documents", "{\"\$where\":\"return true\"}") }
        assertFalse(session.isClosed()) // Local validation does not contact the server or destroy an existing cursor.
    }
    fun writes() {
        val writer = ConnectionProfile(profile.kind, profile.host, profile.port, profile.database, "writer", trustStore = profile.trustStore)
        connect(writer).use { session -> connect(writer).use { other ->
            session.collections(); other.collections()
            val id = "write-fixture-${java.util.UUID.randomUUID()}"
            val first = "{\"_id\":\"$id\",\"name\":\"0007\",\"nested\":{\"number\":1},\"items\":[null,2]}"
            fun read(s: MongoSession) = s.find("documents", "{\"_id\":\"$id\"}").documents.single().json!!
            val insert = session.previewRecord(RecordDraft(MutationKind.INSERT, "documents", replacement = first))
            val result = session.confirmRecord(insert.token)
            assertEquals(result.message, MutationStatus.COMMITTED, result.status)
            assertThrows(WorkspaceRejected::class.java) { session.confirmRecord(insert.token) }
            val old = read(session)
            val stale = session.previewRecord(RecordDraft(MutationKind.UPDATE, "documents", original = old, replacement = old.replace("0007", "stale")))
            val concurrent = other.previewRecord(RecordDraft(MutationKind.UPDATE, "documents", original = read(other), replacement = first.replace("0007", "changed")))
            val changed = other.confirmRecord(concurrent.token)
            assertEquals(changed.message, MutationStatus.COMMITTED, changed.status)
            assertEquals(MutationStatus.CONFLICT, session.confirmRecord(stale.token).status)
            val current = read(session)
            val remove = session.previewRecord(RecordDraft(MutationKind.DELETE, "documents", original = current))
            assertEquals(MutationStatus.COMMITTED, session.confirmRecord(remove.token).status)
            assertTrue(session.find("documents", "{\"_id\":\"$id\"}").documents.isEmpty())
        } }
    }
    fun identityAndAuth() {
        for (p in listOf(
            ConnectionProfile(DatabaseKind.MONGODB, "127.0.0.1", profile.port, profile.database, profile.username, trustStore = profile.trustStore),
            ConnectionProfile(DatabaseKind.MONGODB, "localhost", profile.port, profile.database, profile.username),
        )) assertEquals(ErrorKind.TLS, Failures.classify(assertThrows(Exception::class.java) { connect(p).close() }).kind)
        val wrong = assertThrows(Exception::class.java) { connect(pass = "invalid").close() }
        assertEquals(ErrorKind.AUTHENTICATION, Failures.classify(wrong).kind)
        val source = ConnectionProfile(DatabaseKind.MONGODB, "localhost", profile.port, profile.database, profile.username, trustStore = profile.trustStore, authDatabase = "synthetic")
        assertEquals(ErrorKind.AUTHENTICATION, Failures.classify(assertThrows(Exception::class.java) { connect(source).close() }).kind)
        control(profile.username).use { reader ->
            val error = assertThrows(MongoCommandException::class.java) {
                reader.getDatabase("synthetic").runCommand(BsonDocument.parse("{\"insert\":\"documents\",\"documents\":[{\"forbidden\":true}]}"))
            }
            assertEquals(13, error.errorCode)
        }
    }
    private fun control(user: String = "controller"): com.mongodb.client.MongoClient {
        val spec = profile.trustStore!!
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(SqlServerTrustManager("${profile.host}\n${spec.type}\n${spec.path}")), null) }
        return MongoClients.create(MongoClientSettings.builder()
            .applyToClusterSettings { it.hosts(listOf(ServerAddress(profile.host, profile.port))).mode(com.mongodb.connection.ClusterConnectionMode.SINGLE) }
            .applyToSslSettings { it.enabled(true).context(ssl) }.timeout(30, TimeUnit.SECONDS)
            .credential(MongoCredential.createScramSha256Credential(user, "admin", password.toCharArray()))
            .applicationName("DataSailFixture").retryReads(false).retryWrites(false).build())
    }
    fun deadlinesAndCancellation() = control().use { admin ->
        fun configure(): Long = admin.getDatabase("admin").runCommand(BsonDocument.parse("""{"configureFailPoint":"failCommand","mode":{"times":1},"data":{"failCommands":["find"],"appName":"DataSail","blockConnection":true,"blockTimeMS":20000}}"""))["count"].let { (it as Number).toLong() }
        fun reset() { admin.getDatabase("admin").runCommand(BsonDocument.parse("""{"configureFailPoint":"failCommand","mode":"off"}""")) }
        try {
            connect().use { session ->
                session.collections(); configure()
                val start = System.nanoTime()
                assertThrows(Exception::class.java) { session.find("documents", "{}") }
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                assertTrue("Deadline $elapsed", elapsed in 7000..16000); assertTrue(session.isClosed())
            }
            reset()
            connect().use { session ->
                session.collections(); val entered = configure()
                val done = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
                val worker = Thread { try { session.find("documents", "{}") } catch (e: Throwable) { failure.set(e) } finally { done.countDown() } }
                worker.start()
                admin.getDatabase("admin").runCommand(BsonDocument.parse("{\"waitForFailPoint\":\"failCommand\",\"timesEntered\":${entered + 1}}"))
                val cancelStart = System.nanoTime()
                session.close()
                assertTrue("Cancel must finish checked-out socket", done.await(3, TimeUnit.SECONDS)); assertNotNull(failure.get()); assertTrue(session.isClosed())
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelStart) < 3000)
            }
        } finally { reset() }
    }
}
