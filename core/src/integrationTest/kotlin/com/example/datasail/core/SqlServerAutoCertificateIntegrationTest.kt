package com.example.datasail.core

import java.io.File
import java.util.Properties
import org.junit.Test

class SqlServerAutoCertificateIntegrationTest {
    @Test fun automaticSelfSignedCertificateOverIpRequiresExplicitFingerprintTrust() {
        val config = Properties().apply { File(System.getProperty("datasail.sqlserver.config")).inputStream().use(::load) }
        val profile = ConnectionProfile(DatabaseKind.SQL_SERVER, "127.0.0.1", config.getProperty("port").toInt(), "master", "sa")
        com.example.datasail.fixture.CertificateTrustContract.verify(profile, config.getProperty("password"))
    }
}
