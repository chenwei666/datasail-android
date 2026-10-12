package com.example.datasail.core

import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties

interface SqlConnector {
    val capabilities: SqlCapabilities
    fun connect(profile: ConnectionProfile, credentials: Credentials): Connection
}

class JdbcConnector : SqlConnector {
    init { DriverLogging.disableRawMessages() }
    override val capabilities = SqlCapabilities()
    override fun connect(profile: ConnectionProfile, credentials: Credentials): Connection {
        val driver: Driver = when (profile.kind) {
            DatabaseKind.SQL_SERVER -> com.microsoft.sqlserver.jdbc.SQLServerDriver()
            DatabaseKind.MYSQL -> org.mariadb.jdbc.Driver()
        }
        val properties = safeProperties(profile)
        return credentials.usePassword { password ->
            properties.setProperty("password", password)
            try { driver.connect(url(profile), properties) ?: throw SQLException("Unsupported JDBC scheme", "08001") }
            finally { properties.remove("password") }
        }
    }

    companion object {
        internal fun url(profile: ConnectionProfile): String = when (profile.kind) {
            DatabaseKind.SQL_SERVER -> "jdbc:sqlserver://${profile.host}:${profile.port}"
            DatabaseKind.MYSQL -> "jdbc:mariadb://${profile.host}:${profile.port}/${profile.database}"
        }
        internal fun safeProperties(profile: ConnectionProfile) = Properties().apply {
            setProperty("user", profile.username)
            profile.trustStore?.let {
                setProperty("trustStore", it.path)
                setProperty("trustStoreType", it.type)
                // This store contains only explicitly imported public CA certificates, not keys.
                setProperty("trustStorePassword", "changeit")
            }
            when (profile.kind) {
                DatabaseKind.SQL_SERVER -> {
                    setProperty("databaseName", profile.database)
                    setProperty("authentication", "SqlPassword")
                    setProperty("encrypt", "true")
                    setProperty("trustServerCertificate", "false")
                    setProperty("trustManagerClass", SqlServerTrustManager::class.java.name)
                    val trust = profile.trustStore
                    require(trust == null || ('\n' !in trust.path && '\n' !in trust.type))
                    setProperty("trustManagerConstructorArg", profile.host + "\n" + (trust?.type ?: "PKCS12") + "\n" + (trust?.path ?: ""))
                    setProperty("loginTimeout", "5")
                    setProperty("socketTimeout", "15000")
                    setProperty("connectRetryCount", "0")
                    setProperty("responseBuffering", "adaptive")
                    setProperty("applicationName", "DataSail")
                }
                DatabaseKind.MYSQL -> {
                    setProperty("sslMode", "verify-full")
                    setProperty("tlsSocketType", "DATASAIL_VERIFIED")
                    setProperty("connectTimeout", "5000")
                    setProperty("socketTimeout", "15000")
                    setProperty("allowLocalInfile", "false")
                    setProperty("allowMultiQueries", "false")
                    setProperty("allowPublicKeyRetrieval", "false")
                }
            }
        }
    }
}

internal object DriverLogging {
    // Hold strong logger references; JUL otherwise permits them to be garbage-collected.
    private val sqlServerLogger = java.util.logging.Logger.getLogger("com.microsoft.sqlserver.jdbc")
    fun disableRawMessages() {
        System.setProperty("mariadb.logging.disable", "true")
        sqlServerLogger.level = java.util.logging.Level.OFF
    }
}
