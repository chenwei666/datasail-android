package com.example.datasail.core

import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CertificateTrustTest {
    private fun certificate(name: String = "valid") = CertificateFactory.getInstance("X.509").generateCertificate(
        Base64.getMimeDecoder().decode(javaClass.getResourceAsStream("/test-leaf-$name.b64")!!.readBytes()).inputStream()) as X509Certificate
    private fun manager(pin: String = "", review: String = "") = SqlServerTrustManager("127.0.0.1\nPKCS12\n\n$pin\n$review")
    @Test fun `untrusted CN-only leaf is rejected by default and accepted only by exact pin`() {
        val cert = certificate()
        assertNull(cert.subjectAlternativeNames)
        assertThrows(CertificateException::class.java) { manager().checkServerTrusted(arrayOf(cert), "RSA") }
        manager(CertificateTrust.fingerprint(cert)).checkServerTrusted(arrayOf(cert), "RSA")
        assertThrows(CertificateException::class.java) { manager("0".repeat(64)).checkServerTrusted(arrayOf(cert), "RSA") }
    }
    @Test fun `pin never permits an expired certificate or an empty chain`() {
        val cert = certificate("expired")
        assertThrows(CertificateExpiredException::class.java) { manager(CertificateTrust.fingerprint(cert)).checkServerTrusted(arrayOf(cert), "RSA") }
        assertThrows(CertificateException::class.java) { manager("0".repeat(64)).checkServerTrusted(emptyArray(), "RSA") }
    }
    @Test fun `wrong pin cannot fall back even when CA and SAN verification would succeed`() {
        val cert = certificate("san")
        val path = java.io.File.createTempFile("datasail-public-test-anchor", ".p12")
        try {
            val store = java.security.KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("public-anchor", cert) }
            path.outputStream().use { store.store(it, "changeit".toCharArray()) }
            val prefix = "localhost\nPKCS12\n${path.path}"
            SqlServerTrustManager(prefix).checkServerTrusted(arrayOf(cert), "RSA")
            assertThrows(CertificateException::class.java) {
                SqlServerTrustManager("$prefix\n${"0".repeat(64)}\n").checkServerTrusted(arrayOf(cert), "RSA")
            }
        } finally { path.delete() }
    }
    @Test fun `HTTPS hostname exception requires both expected endpoint and exact valid certificate`() {
        val cert = certificate()
        val session = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(javax.net.ssl.SSLSession::class.java)) { _, method, _ ->
            if (method.name == "getPeerCertificates") arrayOf(cert) else throw UnsupportedOperationException()
        } as javax.net.ssl.SSLSession
        val pin = CertificateTrust.fingerprint(cert)
        assertTrue(CertificateTrust.matchesSession("127.0.0.1", "127.0.0.1", pin, session))
        assertFalse(CertificateTrust.matchesSession("127.0.0.2", "127.0.0.1", pin, session))
        assertFalse(CertificateTrust.matchesSession("127.0.0.1", "127.0.0.1", "0".repeat(64), session))
    }
    @Test fun `inspection always aborts handshake even with an already matching pin`() {
        val cert = certificate()
        val pin = CertificateTrust.fingerprint(cert)
        CertificateReview().use { review ->
            assertThrows(CertificateException::class.java) { manager(pin, review.token).checkServerTrusted(arrayOf(cert), "RSA") }
            assertEquals(pin, review.preview()!!.sha256)
            assertTrue(review.preview()!!.isCurrent())
        }
    }
    @Test fun `cancelled inspection cannot accept trust or deliver late metadata`() {
        val cert = certificate()
        val review = CertificateReview()
        val tls = manager(CertificateTrust.fingerprint(cert), review.token)
        review.close()
        assertThrows(CertificateException::class.java) { tls.checkServerTrusted(arrayOf(cert), "RSA") }
        assertNull(review.preview())
    }
    @Test fun `concurrent reviews are isolated and an expired preview is not confirmable`() {
        CertificateReview().use { first -> CertificateReview().use { second ->
            val cert = certificate("expired")
            assertThrows(CertificateException::class.java) { manager(review = first.token).checkServerTrusted(arrayOf(cert), "RSA") }
            assertNull(second.preview())
            assertFalse(first.preview()!!.isCurrent())
        } }
    }
    @Test fun `pin options propagate without plaintext downgrade or JDBC trust-all`() {
        for (kind in listOf(DatabaseKind.SQL_SERVER, DatabaseKind.MYSQL, DatabaseKind.MARIADB, DatabaseKind.POSTGRESQL)) {
            val p = ConnectionProfile(kind, "127.0.0.1", kind.defaultPort, "synthetic", "reader", certificatePin = "a".repeat(64))
            val properties = JdbcConnector.safeProperties(p)
            assertTrue(properties.getProperty("datasailTlsConfiguration").contains("a".repeat(64)))
            when (kind) {
                DatabaseKind.SQL_SERVER -> { assertEquals("true", properties.getProperty("encrypt")); assertEquals("false", properties.getProperty("trustServerCertificate")) }
                DatabaseKind.POSTGRESQL -> assertEquals("verify-ca", properties.getProperty("sslmode"))
                else -> assertEquals("verify-full", properties.getProperty("sslMode"))
            }
        }
    }
}
