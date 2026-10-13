package com.example.datasail.core

import java.io.FileInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Shared policy via SQL Server's documented trustManagerClass extension.
 * Defaults to chain + SAN (no desktop JNDI/CN fallback). An explicitly approved leaf pin
 * replaces chain/name verification, but still enforces validity and TLS proof of key ownership.
 * Inspection always throws before authentication, including when a matching pin exists.
 */
class SqlServerTrustManager(configuration: String) : X509TrustManager {
    private val parts = configuration.split('\n')
    private val host: String
    private val delegate: X509TrustManager
    init {
        require(parts.size == 3 || parts.size == 5)
        require(parts.getOrNull(3).isNullOrEmpty() || parts[3].matches(Regex("[a-f0-9]{64}")))
        host = parts[0]
        val keyStore = if (parts[2].isEmpty()) null else KeyStore.getInstance(parts[1]).apply {
            FileInputStream(parts[2]).use { load(it, "changeit".toCharArray()) }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Missing server certificate")
        parts.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { CertificateReview.inspect(it, chain[0]) }
        parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { CertificateTrust.checkPin(chain[0], it); return }
        delegate.checkServerTrusted(chain, authType)
        if (chain.isEmpty() || !matchesSan(host, chain[0].subjectAlternativeNames)) throw CertificateException("Server SAN identity mismatch")
    }
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = delegate.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    companion object {
        internal fun matchesSan(host: String, alternatives: Collection<List<*>>?): Boolean {
            val ip = host.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))
            val normalized = host.lowercase(Locale.ROOT)
            return alternatives.orEmpty().any { item ->
                val value = item.getOrNull(1) as? String ?: return@any false
                when {
                    ip && item.firstOrNull() == 7 -> try {
                        // Both inputs are numeric IPs from validated profiles/X.509 SANs; no DNS resolution.
                        value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) && InetAddress.getByName(host) == InetAddress.getByName(value)
                    } catch (_: Exception) { false }
                    !ip && item.firstOrNull() == 2 -> {
                        val dns = value.lowercase(Locale.ROOT)
                        dns == normalized || (dns.startsWith("*.") && dns.count { it == '.' } >= 2 &&
                            normalized.substringAfter('.', "") == dns.substring(2) && normalized.substringBefore('.').isNotEmpty())
                    }
                    else -> false
                }
            }
        }
    }
}
