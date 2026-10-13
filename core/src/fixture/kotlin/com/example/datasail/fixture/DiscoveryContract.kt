package com.example.datasail.fixture

import com.example.datasail.core.*
import org.junit.Assert.*

/** Same real protocol discovery assertion in JVM and the installed APK. */
object DiscoveryContract {
    fun verify(profile: ConnectionProfile, password: String, expected: String = profile.database) {
        CertificateTrustContract.verify(profile, password)
        val bootstrap = ConnectionProfile(profile.kind, profile.host, profile.port,
            DiscoveryDefaults.database(profile.kind), profile.username, trustStore = profile.trustStore, authDatabase = profile.authDatabase)
        val target = if (profile.kind == DatabaseKind.OPENSEARCH) {
            // This pinned fixture grants only synthetic: broad CAT metadata is deliberately denied.
            val failure = assertThrows(Exception::class.java) {
                ServerDatabaseDiscovery().use { it.scan(bootstrap, Credentials(password.toCharArray())) }
            }
            assertEquals(ErrorKind.PERMISSION, Failures.classify(failure).kind)
            profile
        } else bootstrap
        val result = ServerDatabaseDiscovery().use { it.scan(target, Credentials(password.toCharArray())) }
        assertTrue("Expected authorized target in server discovery", expected in result.names)
        assertFalse(result.limited)
        if (profile.kind == DatabaseKind.POSTGRESQL) assertTrue(" synthetic " in result.names)
        if (profile.kind.isKeyValue) {
            assertEquals(setOf("0", "5"), result.names.toSet())
            assertFalse("Empty logical DB must not be invented", "1" in result.names)
            val denied = ConnectionProfile(profile.kind, profile.host, profile.port, "0", "noinfo", trustStore = profile.trustStore)
            val error = assertThrows(Exception::class.java) { ServerDatabaseDiscovery().use { it.scan(denied, Credentials(password.toCharArray())) } }
            assertEquals(ErrorKind.PERMISSION, Failures.classify(error).kind)
        }
        if (profile.kind.isDocument) assertEquals(listOf("synthetic"), result.names)
    }
}
