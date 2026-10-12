package com.example.datasail.core

import org.mariadb.jdbc.Configuration
import org.mariadb.jdbc.HostAddress
import org.mariadb.jdbc.export.ExceptionFactory
import org.mariadb.jdbc.plugin.TlsSocketPlugin
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

/** No trust-all or deferred authentication-based certificate trust, even if the driver offers it. */
class StrictTlsPlugin : TlsSocketPlugin {
    override fun type() = "DATASAIL_VERIFIED"
    override fun getTrustManager(conf: Configuration, exceptionFactory: ExceptionFactory, hostAddress: HostAddress): Array<TrustManager> {
        val store = conf.trustStore()?.let { path ->
            KeyStore.getInstance(conf.trustStoreType() ?: "PKCS12").apply {
                java.io.FileInputStream(path).use { load(it, conf.trustStorePassword()?.toCharArray()) }
            }
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers
    }
    override fun getKeyManager(conf: Configuration, exceptionFactory: ExceptionFactory): Array<KeyManager>? = null
    override fun verify(host: String, sslSession: SSLSession, serverThreadId: Long) {
        val certificate = sslSession.peerCertificates.firstOrNull() as? X509Certificate
        if (certificate == null || !SqlServerTrustManager.matchesSan(host, certificate.subjectAlternativeNames))
            throw javax.net.ssl.SSLPeerUnverifiedException("Server SAN identity mismatch")
    }
}
