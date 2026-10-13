package com.example.datasail.fixture

import com.example.datasail.core.*
import org.junit.Assert.*

/** Same pinning contract on real engines in JVM and ART; IP is absent from the fixture SAN. */
object CertificateTrustContract {
    fun verify(base: ConnectionProfile, password: String) {
        fun profile(pin: String? = null, token: String? = null) = ConnectionProfile(base.kind, "127.0.0.1", base.port,
            base.database, base.username, authDatabase = base.authDatabase, certificatePin = pin, certificateReviewToken = token)
        fun scan(p: ConnectionProfile, secret: String = password) = ServerDatabaseDiscovery().use {
            it.scan(p, Credentials(secret.toCharArray()))
        }
        assertEquals(ErrorKind.TLS, Failures.classify(assertThrows(Exception::class.java) { scan(profile()) }).kind)
        val preview = CertificateReview().use { review ->
            val failure = assertThrows(Exception::class.java) { scan(profile(token = review.token), "") }
            val reason = generateSequence<Throwable>(failure) { it.cause }.take(6).joinToString(" -> ") {
                it.javaClass.simpleName + ": " + it.message.orEmpty().replace(password, "[REDACTED]").take(400)
            }
            review.preview().also { assertNotNull("TLS inspection must return public certificate metadata: $reason", it) }!!
        }
        assertTrue(preview.isCurrent())
        val pinned = profile(pin = preview.sha256)
        assertFalse(scan(pinned).names.isEmpty())
        // JDBC's regular target login is separate from server discovery.
        if (base.kind.isSql) Credentials(password.toCharArray()).use { JdbcConnector().connect(pinned, it).close() }
        val wrongPin = (if (preview.sha256[0] == '0') "1" else "0") + preview.sha256.substring(1)
        assertEquals(ErrorKind.TLS, Failures.classify(assertThrows(Exception::class.java) { scan(profile(pin = wrongPin)) }).kind)
        val closedReview = CertificateReview().apply { close() }
        assertThrows(Exception::class.java) { scan(profile(pin = preview.sha256, token = closedReview.token), "") }
        assertNull(closedReview.preview())
    }
}
