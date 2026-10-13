package com.example.datasail.core

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSession

/** Public metadata only. Inspection always aborts TLS before any database authentication. */
data class CertificatePreview(val sha256: String, val subject: String, val issuer: String,
    val notBefore: Long, val notAfter: Long) {
    fun isCurrent(now: Long = System.currentTimeMillis()) = now in notBefore..notAfter
}

class CertificateReview : AutoCloseable {
    val token: String = UUID.randomUUID().toString()
    private val observed = AtomicReference<CertificatePreview?>()
    init { pending[token] = this }
    fun preview(): CertificatePreview? = observed.get()
    override fun close() { pending.remove(token, this) }
    companion object {
        private val pending = ConcurrentHashMap<String, CertificateReview>()
        internal fun inspect(token: String, certificate: X509Certificate): Nothing {
            val review = pending[token] ?: throw CertificateException("Certificate review closed")
            fun safe(value: String) = value.filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }.take(256)
            review.observed.compareAndSet(null, CertificatePreview(CertificateTrust.fingerprint(certificate),
                safe(certificate.subjectX500Principal.name), safe(certificate.issuerX500Principal.name),
                certificate.notBefore.time, certificate.notAfter.time))
            throw CertificateException("Certificate inspection only; authentication prohibited")
        }
    }
}

object CertificateTrust {
    fun fingerprint(certificate: X509Certificate): String {
        val encoded = certificate.encoded
        if (encoded.size > 65536) throw CertificateException("Certificate exceeds limit")
        return MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
    }
    internal fun checkPin(certificate: X509Certificate, pin: String) {
        if (fingerprint(certificate) != pin) throw CertificateException("Server certificate changed")
        certificate.checkValidity()
    }
    fun matchesSession(host: String, expectedHost: String, pin: String, session: SSLSession): Boolean = try {
        host.equals(expectedHost, true) && (session.peerCertificates.firstOrNull() as? X509Certificate)?.let {
            checkPin(it, pin); true
        } == true
    } catch (_: Exception) { false }
    internal fun configuration(profile: ConnectionProfile): String = listOf(profile.host,
        profile.trustStore?.type ?: "PKCS12", profile.trustStore?.path.orEmpty(),
        profile.certificatePin.orEmpty(), profile.certificateReviewToken.orEmpty()).joinToString("\n").also {
        require(it.split('\n').size == 5)
    }
}
