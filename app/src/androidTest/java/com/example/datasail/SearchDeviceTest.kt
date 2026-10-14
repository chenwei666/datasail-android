package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.*
import com.example.datasail.fixture.SearchContract
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Properties

class SearchDeviceTest {
    private fun fixture(block: SearchContract.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Properties().apply { File(context.filesDir, "search-fixture.properties").inputStream().use(::load) }
        val cert = File(context.filesDir, "search-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val trust = File(context.cacheDir, "search-trust.p12")
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("fixture", cert) }
        trust.outputStream().use { store.store(it, "changeit".toCharArray()) }
        try { SearchContract(ConnectionProfile(DatabaseKind.valueOf(config.getProperty("kind")), "localhost", config.getProperty("port").toInt(), "synthetic", "reader",
            trustStore = TrustStoreSpec(trust.path)), config.getProperty("password"), config.getProperty("faultPort").toInt()).block() }
        finally { trust.delete() }
    }
    @Test fun singleObjectWritesAndConflicts() = fixture { writes() }
    @Test fun readsAndBoundsOnArt() = fixture { reads() }
    @Test fun identityAndAuthOnArt() = fixture { identityAndAuth() }
    @Test fun deadlinesAndCancellationOnArt() = fixture { transportBoundsAndCancellation() }
}
