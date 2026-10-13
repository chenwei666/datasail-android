package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in same APK against three separately provisioned real server engines. */
class JdbcWorkspaceDeviceTest {
    private fun fixture(block: (ConnectionProfile, Properties) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Properties().apply { File(context.filesDir, "jdbc-fixture.properties").inputStream().use(::load) }
        val cert = File(context.filesDir, "jdbc-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val trust = File(context.cacheDir, "jdbc-trust.p12")
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("fixture", cert) }
        trust.outputStream().use { store.store(it, "changeit".toCharArray()) }
        try { block(ConnectionProfile(DatabaseKind.valueOf(config.getProperty("kind")), "localhost", config.getProperty("port").toInt(),
            "synthetic", "datasail", trustStore = TrustStoreSpec(trust.path)), config) }
        finally { trust.delete() }
    }
    @Test fun tlsIdentityAndWorkbenchReadOnArt() = fixture { profile, config ->
        com.example.datasail.fixture.DiscoveryContract.verify(profile, config.getProperty("password"))
        fun connection(p: ConnectionProfile) = Credentials(config.getProperty("password").toCharArray()).use { JdbcConnector().connect(p, it) }
        for (p in listOf(ConnectionProfile(profile.kind, "127.0.0.1", profile.port, "synthetic", "datasail", trustStore = profile.trustStore),
            ConnectionProfile(profile.kind, "localhost", profile.port, "synthetic", "datasail"))) {
            val failure = assertThrows(Exception::class.java) { connection(p).close() }
            val detail = generateSequence(failure as Throwable) { it.cause }.take(8).joinToString(" <- ") { it.javaClass.name } +
                if (Failures.classify(failure).kind == ErrorKind.TIMEOUT) Thread.getAllStackTraces().values.filter { frames -> frames.any { it.className.startsWith("org.postgresql") } }.joinToString("\n") { it.joinToString("\n") } else ""
            assertEquals(detail, ErrorKind.TLS, Failures.classify(failure).kind)
        }
        connection(profile).use { raw -> JdbcBrowseSession(raw, profile).use { session ->
            val schema = if (profile.kind == DatabaseKind.POSTGRESQL) "public" else "synthetic"
            val table = session.tables(schema).single { it.name == "workbench_fixture" }
            assertEquals(100, session.page(table).rows.size)
            assertEquals(5, session.page(table, 100).rows.size)
            assertEquals(listOf("id"), session.describe(table).keys.map { it.name })
            assertEquals(DbValue.Integer(105), session.select("SELECT COUNT(*) FROM workbench_fixture").rows.single().single())
            assertEquals(DbValue.Text("0007"), session.select("SELECT '0007'").rows.single().single())
            assertFalse(session.writable)
            assertThrows(WorkspaceRejected::class.java) { session.setWritable(true) }
            assertThrows(WorkspaceRejected::class.java) { session.select("DELETE FROM workbench_fixture") }
        } }
    }
    @Test fun timeoutAndCancellationCloseResourcesWithoutReplay() = fixture { profile, config ->
        fun connect() = Credentials(config.getProperty("password").toCharArray()).use { JdbcConnector().connect(profile, it) }
        val delay = if (profile.kind == DatabaseKind.POSTGRESQL) "SELECT pg_sleep(20)" else "SELECT SLEEP(20)"
        connect().use { raw -> JdbcBrowseSession(raw, profile).use { session ->
            val start = System.nanoTime()
            if (profile.kind == DatabaseKind.POSTGRESQL) {
                val failure = assertThrows(java.sql.SQLException::class.java) { session.select(delay) }
                assertTrue(Failures.classify(failure).kind in setOf(ErrorKind.TIMEOUT, ErrorKind.CANCELLED))
            } else {
                // MySQL SLEEP reports interruption as the numeric result 1; MariaDB may throw.
                try { assertEquals(DbValue.Integer(1), session.select(delay).rows.single().single()) }
                catch (error: java.sql.SQLException) { assertTrue(Failures.classify(error).toString() + " " + generateSequence(error as Throwable) { it.cause }.take(8).joinToString { it.javaClass.name }, Failures.classify(error).kind in setOf(ErrorKind.TIMEOUT, ErrorKind.CANCELLED)) }
            }
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) in 8..19)
        } }
        val raw = connect()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val statement = AtomicReference<java.sql.Statement?>()
        JdbcBrowseSession(raw, profile) { s -> statement.set(s) }.use { session ->
            val worker = Thread {
                try { started.countDown(); session.select(delay) } catch (_: Exception) {} finally { finished.countDown() }
            }
            worker.start(); assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(1000)
            try { statement.get()?.cancel() } finally { session.close() }
            assertTrue(finished.await(20, TimeUnit.SECONDS)); worker.join(1000)
            assertTrue(raw.isClosed)
        }
    }
}
