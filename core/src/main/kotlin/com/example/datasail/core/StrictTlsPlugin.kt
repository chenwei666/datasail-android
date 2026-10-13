package com.example.datasail.core

import org.mariadb.jdbc.Configuration
import org.mariadb.jdbc.HostAddress
import org.mariadb.jdbc.export.ExceptionFactory
import org.mariadb.jdbc.plugin.TlsSocketPlugin
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager

/** No trust-all or deferred authentication-based certificate trust, even if the driver offers it. */
class StrictTlsPlugin : TlsSocketPlugin {
    override fun type() = "DATASAIL_VERIFIED"
    override fun getTrustManager(conf: Configuration, exceptionFactory: ExceptionFactory, hostAddress: HostAddress): Array<TrustManager> {
        // The shared manager checks either CA + SAN or the explicitly approved leaf fingerprint.
        val config = conf.nonMappedOptions().getProperty("datasailTlsConfiguration")
            ?: throw java.security.cert.CertificateException("Missing TLS identity")
        return arrayOf(SqlServerTrustManager(config))
    }

    override fun getKeyManager(conf: Configuration, exceptionFactory: ExceptionFactory): Array<KeyManager>? = null
    // Identity is checked during the handshake, before the driver can send authentication.
    override fun verify(host: String, sslSession: SSLSession, serverThreadId: Long) = Unit
}
