package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.SQLException
import java.util.Properties

class UntrustedCertificateTest {
    @Test fun `untrusted CA is rejected before credentials can authenticate`() {
        val config = Properties().apply { File(System.getProperty("datasail.test.config")).inputStream().use(::load) }
        val profile = ConnectionProfile(DatabaseKind.valueOf(config.getProperty("kind")), config.getProperty("host"),
            config.getProperty("port").toInt(), config.getProperty("database"), config.getProperty("username"))
        Credentials(config.getProperty("password").toCharArray()).use { secret ->
            val error = assertThrows(SQLException::class.java) {
                JdbcConnector().connect(profile, secret).use { fail("Untrusted certificate accepted") }
            }
            assertEquals(ErrorKind.TLS, Failures.classify(error).kind)
        }
    }
}
