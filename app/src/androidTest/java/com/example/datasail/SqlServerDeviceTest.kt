package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Properties

/** Explicit opt-in fixture, not executed by the normal UI smoke task. No credentials in arguments or APK. */
class SqlServerDeviceTest {
    @Test fun strictTlsBrowseEditAndConflictOnArt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Properties().apply { File(context.filesDir, "fixture.properties").inputStream().use(::load) }
        val cert = File(context.filesDir, "fixture-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val trust = File(context.cacheDir, "fixture-trust.p12")
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("fixture", cert) }
        trust.outputStream().use { store.store(it, "changeit".toCharArray()) }
        fun profile(trusted: Boolean = true, host: String = "10.0.2.2") = ConnectionProfile(DatabaseKind.SQL_SERVER, host, config.getProperty("port").toInt(),
            "datasail_v020", "sa", trustStore = if (trusted) TrustStoreSpec(trust.path) else null)
        fun connection(p: ConnectionProfile = profile()) = Credentials(config.getProperty("password").toCharArray()).use { JdbcConnector().connect(p, it) }
        try {
            val untrusted = assertThrows(Exception::class.java) { connection(profile(false)).close() }
            assertEquals(ErrorKind.TLS, Failures.classify(untrusted).kind)
            val mismatch = assertThrows(Exception::class.java) { connection(profile(host = "127.0.0.1")).close() }
            assertEquals(ErrorKind.TLS, Failures.classify(mismatch).kind)
            connection().use { raw -> SqlServerSession(raw, profile()).use { session ->
                val table = session.tables("dbo").single { it.name == "records" }
                val page = session.page(table)
                assertTrue(page.rows.isNotEmpty())
                assertTrue(page.rows[0][1] is DbValue.Text)
                session.setWritable(true)
                val insert = session.preview(MutationKind.INSERT, null, mapOf("label" to CellInput("Android · 数舟 · 00123")))
                assertEquals(MutationStatus.COMMITTED, session.confirm(insert.token).status)
                val current = session.page(table)
                val row = current.rows.indexOfFirst { it[1] == DbValue.Text("Android · 数舟 · 00123") }
                assertTrue(row >= 0)
                val update = session.preview(MutationKind.UPDATE, row, mapOf("label" to CellInput("updated")))
                assertEquals(MutationStatus.COMMITTED, session.confirm(update.token).status)
                session.page(table)
                val stale = session.preview(MutationKind.UPDATE, row, mapOf("label" to CellInput("stale")))
                connection().use { other -> other.createStatement().use { it.executeUpdate("UPDATE dbo.records SET label=N'concurrent Android' WHERE label=N'updated'") } }
                assertEquals(MutationStatus.CONFLICT, session.confirm(stale.token).status)
                session.page(table)
                val delete = session.preview(MutationKind.DELETE, row, emptyMap())
                assertEquals(MutationStatus.COMMITTED, session.confirm(delete.token).status)
                assertThrows(WorkspaceRejected::class.java) { session.confirm(delete.token) }
            } }
        } finally { trust.delete() }
    }
}
