package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.*
import com.example.datasail.fixture.MongoContract
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Properties

class MongoDeviceTest {
    private fun fixture(block: MongoContract.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Properties().apply { File(context.filesDir, "mongo-fixture.properties").inputStream().use(::load) }
        val cert = File(context.filesDir, "mongo-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val trust = File(context.cacheDir, "mongo-trust.p12")
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("fixture", cert) }
        trust.outputStream().use { store.store(it, "changeit".toCharArray()) }
        try { MongoContract(ConnectionProfile(DatabaseKind.MONGODB, "localhost", config.getProperty("port").toInt(), "synthetic", "reader",
            trustStore = TrustStoreSpec(trust.path)), config.getProperty("password")).block() }
        finally { trust.delete() }
    }
    @Test fun singleObjectWritesAndConflicts() = fixture { writes() }
    @Test fun readsAndBoundsOnArt() = fixture { reads() }
    @Test fun identityAndAuthOnArt() = fixture { identityAndAuth() }
    @Test fun deadlinesAndCancellationOnArt() = fixture { deadlinesAndCancellation() }
}
