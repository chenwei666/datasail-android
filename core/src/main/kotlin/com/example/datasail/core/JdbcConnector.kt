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
    override fun connect(profile: ConnectionProfile, credentials: Credentials): Connection = connect(profile, credentials, false)
    fun connectServer(profile: ConnectionProfile, credentials: Credentials): Connection = connect(profile, credentials, true)
    private fun connect(profile: ConnectionProfile, credentials: Credentials, discovery: Boolean): Connection {
        val driver: Driver = when (profile.kind) {
            DatabaseKind.SQL_SERVER -> com.microsoft.sqlserver.jdbc.SQLServerDriver()
            DatabaseKind.MYSQL, DatabaseKind.MARIADB -> org.mariadb.jdbc.Driver()
            DatabaseKind.POSTGRESQL -> org.postgresql.Driver()
            DatabaseKind.ELASTICSEARCH, DatabaseKind.OPENSEARCH -> throw WorkspaceRejected("搜索引擎使用独立工作台")
            DatabaseKind.MONGODB -> throw WorkspaceRejected("文档引擎使用独立工作台")
            DatabaseKind.REDIS, DatabaseKind.VALKEY -> throw WorkspaceRejected("键值引擎使用独立工作台")
            DatabaseKind.SQLITE -> throw WorkspaceRejected("SQLite 使用 Android 本地文件连接")
        }
        val properties = safeProperties(profile)
        if (discovery && profile.kind == DatabaseKind.SQL_SERVER) properties.remove("databaseName")
        val targetUrl = if (discovery && profile.kind in setOf(DatabaseKind.MYSQL, DatabaseKind.MARIADB))
            "jdbc:mariadb://${profile.host}:${profile.port}/" else url(profile)
        return credentials.usePassword { password ->
            // SQLPassword validates nonempty credentials before starting TLS. Inspection always
            // aborts in our trust manager; this placeholder is never database authentication.
            properties.setProperty("password", if (profile.certificateReviewToken == null) password else "DataSail-certificate-inspection")
            try {
                if (profile.kind in setOf(DatabaseKind.MYSQL, DatabaseKind.MARIADB)) {
                    verifyMariaDbConfiguration(targetUrl, properties)
                }
                driver.connect(targetUrl, properties) ?: throw SQLException("Unsupported JDBC scheme", "08001")
            }
            finally { properties.remove("password") }
        }
    }

    companion object {
        // Connector/J parses properties reflectively. Fail before opening a socket
        // if shrinking or a driver change causes credentials/TLS policy to be lost.
        internal fun verifyMariaDbConfiguration(targetUrl: String, properties: Properties) {
            val effective = org.mariadb.jdbc.Configuration.parse(targetUrl, properties)
            val valid = effective != null &&
                effective.user().orEmpty() == properties.getProperty("user").orEmpty() &&
                effective.password().orEmpty() == properties.getProperty("password").orEmpty() &&
                effective.sslMode() == org.mariadb.jdbc.export.SslMode.VERIFY_FULL &&
                effective.tlsSocketType() == "DATASAIL_VERIFIED" &&
                effective.connectTimeout() == 15000 && effective.socketTimeout() == 15000 &&
                !effective.allowLocalInfile() && !effective.allowMultiQueries() &&
                !effective.allowPublicKeyRetrieval() && effective.useServerPrepStmts()
            if (!valid) throw SQLException("Database driver did not preserve the required connection policy", "08001")
        }
        internal fun url(profile: ConnectionProfile): String = when (profile.kind) {
            DatabaseKind.SQL_SERVER -> "jdbc:sqlserver://${profile.host}:${profile.port}"
            DatabaseKind.MYSQL, DatabaseKind.MARIADB -> "jdbc:mariadb://${profile.host}:${profile.port}/${profile.database}"
            DatabaseKind.POSTGRESQL -> "jdbc:postgresql://${profile.host}:${profile.port}/${profile.database}"
            DatabaseKind.ELASTICSEARCH, DatabaseKind.OPENSEARCH -> throw WorkspaceRejected("搜索引擎使用独立工作台")
            DatabaseKind.MONGODB -> throw WorkspaceRejected("文档引擎使用独立工作台")
            DatabaseKind.REDIS, DatabaseKind.VALKEY -> throw WorkspaceRejected("键值引擎使用独立工作台")
            DatabaseKind.SQLITE -> throw WorkspaceRejected("SQLite 不使用 JDBC")
        }
        internal fun safeProperties(profile: ConnectionProfile) = Properties().apply {
            setProperty("user", profile.username)
            setProperty("datasailTlsConfiguration", CertificateTrust.configuration(profile))
            profile.trustStore?.let {
                setProperty("trustStore", it.path)
                setProperty("trustStoreType", it.type)
                // This store contains only explicitly imported public CA certificates, not keys.
                setProperty("trustStorePassword", "changeit")
            }
            when (profile.kind) {
                DatabaseKind.ELASTICSEARCH, DatabaseKind.OPENSEARCH -> throw WorkspaceRejected("搜索引擎使用独立工作台")
            DatabaseKind.MONGODB -> throw WorkspaceRejected("文档引擎使用独立工作台")
            DatabaseKind.REDIS, DatabaseKind.VALKEY -> throw WorkspaceRejected("键值引擎使用独立工作台")
            DatabaseKind.SQLITE -> throw WorkspaceRejected("SQLite 不使用 JDBC")
                DatabaseKind.POSTGRESQL -> {
                    setProperty("gssEncMode", "disable")
                    setProperty("sslmode", if (profile.certificatePin == null) "verify-full" else "verify-ca")
                    setProperty("sslfactory", PostgreSqlTlsFactory::class.java.name)
                    setProperty("datasailTlsHost", profile.host)
                    setProperty("connectTimeout", "15")
                    setProperty("socketTimeout", "15")
                    setProperty("loginTimeout", "30")
                    setProperty("cancelSignalTimeout", "3")
                    setProperty("maxResultBuffer", "8M")
                    setProperty("readOnlyMode", "always")
                    setProperty("ApplicationName", "DataSail")
                }
                DatabaseKind.SQL_SERVER -> {
                    setProperty("databaseName", profile.database)
                    setProperty("authentication", "SqlPassword")
                    setProperty("encrypt", "true")
                    setProperty("trustServerCertificate", "false")
                    setProperty("trustManagerClass", SqlServerTrustManager::class.java.name)
                    val trust = profile.trustStore
                    require(trust == null || ('\n' !in trust.path && '\n' !in trust.type))
                    setProperty("trustManagerConstructorArg", CertificateTrust.configuration(profile))
                    setProperty("loginTimeout", "5")
                    setProperty("socketTimeout", "15000")
                    setProperty("connectRetryCount", "0")
                    setProperty("responseBuffering", "adaptive")
                    setProperty("maxResultBuffer", "8M")
                    setProperty("applicationName", "DataSail")
                }
                DatabaseKind.MYSQL, DatabaseKind.MARIADB -> {
                    setProperty("sslMode", "verify-full")
                    setProperty("tlsSocketType", "DATASAIL_VERIFIED")
                    setProperty("connectTimeout", "15000")
                    setProperty("socketTimeout", "15000")
                    setProperty("allowLocalInfile", "false")
                    setProperty("allowMultiQueries", "false")
                    setProperty("useServerPrepStmts", "true") // Typed binary float parameters must not become rounded SQL literals.
                    setProperty("allowPublicKeyRetrieval", "false")
                }
            }
        }
    }
}

internal object DriverLogging {
    // Hold strong logger references; JUL otherwise permits them to be garbage-collected.
    private val sqlServerLogger = java.util.logging.Logger.getLogger("com.microsoft.sqlserver.jdbc")
    private val postgresLogger = java.util.logging.Logger.getLogger("org.postgresql")
    fun disableRawMessages() {
        postgresLogger.level = java.util.logging.Level.OFF
        System.setProperty("mariadb.logging.disable", "true")
        sqlServerLogger.level = java.util.logging.Level.OFF
    }
}
