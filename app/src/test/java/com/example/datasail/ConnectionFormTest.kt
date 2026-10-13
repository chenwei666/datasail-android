package com.example.datasail

import com.example.datasail.core.DatabaseKind
import org.junit.Assert.*
import org.junit.Test

class ConnectionFormTest {
    @Test fun certificateTrustCannotMoveToAnotherEndpointEngineOrConnection() {
        val base = SavedConnection(name = "pin", host = "127.0.0.1", username = "reader")
        val pinned = base.copy(certificateTrust = SavedCertificateTrust(base.id, base.kind, base.host, 1433, "a".repeat(64), 123L))
        assertNotNull(pinned.copy(database = "other", username = "other").withoutMovedTrust().certificateTrust)
        for (changed in listOf(pinned.copy(id = "copy"), pinned.copy(host = "127.0.0.2"), pinned.copy(port = "1434"), pinned.copy(kind = DatabaseKind.MYSQL))) {
            assertFalse(pinned.certificateTrust!!.matches(changed))
            assertNull(changed.withoutMovedTrust().certificateTrust)
        }
    }
    @Test fun tlsScanFailureGivesCertificateGuidanceWithoutSuggestingManualDatabaseBypass() {
        val message = discoveryFailureMessage(javax.net.ssl.SSLHandshakeException("private details"))
        assertTrue(message.contains("获取并核对服务器证书"))
        assertFalse(message.contains("private details"))
        assertFalse(message.contains("手动填写"))
    }
    @Test fun scannedMetadataCannotNormalizeIntoAnotherDatabase() {
        // These can be different PostgreSQL/SQL Server identifiers despite looking similar in a form.
        for (kind in listOf(DatabaseKind.SQL_SERVER, DatabaseKind.POSTGRESQL, DatabaseKind.MYSQL, DatabaseKind.MARIADB)) {
            val connection = SavedConnection(kind = kind)
            assertTrue(connection.canSelectDiscoveredDatabase("synthetic"))
            assertFalse(connection.canSelectDiscoveredDatabase(" synthetic"))
            assertFalse(connection.canSelectDiscoveredDatabase("synthetic "))
            assertFalse(connection.canSelectDiscoveredDatabase("synthetic\n"))
            assertFalse(connection.canSelectDiscoveredDatabase("other.database"))
        }
    }
    @Test fun discoveryExplainsLocalLimitsWithoutLeakingRemoteExceptionText() {
        assertTrue(discoveryFailureMessage(com.example.datasail.core.WorkspaceRejected("索引超过 200 个，请缩小连接中的索引范围")).contains("请缩小"))
        assertFalse(discoveryFailureMessage(IllegalStateException("synthetic-sensitive-response")).contains("synthetic-sensitive-response"))
    }
    @Test fun discoveryDoesNotRequireTargetButStillValidatesEndpointAndBootstrap() {
        val base = SavedConnection(name = "server", host = "localhost", username = "reader")
        DatabaseKind.entries.filter { it != DatabaseKind.SQLITE }.forEach { kind ->
            val server = base.copy(kind = kind, port = kind.defaultPort.toString())
            assertTrue("$kind must allow a blank target", server.discoveryErrors("").isEmpty())
            assertTrue(server.fieldErrors().containsKey("database"))
            assertTrue(server.copy(host = "https://localhost").discoveryErrors("").containsKey("host"))
        }
        assertTrue(base.copy(kind = DatabaseKind.POSTGRESQL).discoveryErrors("invalid/db").containsKey("database"))
        assertTrue(base.copy(kind = DatabaseKind.OPENSEARCH).discoveryErrors("../secret").containsKey("database"))
    }
    @Test fun validatesActualEngineFieldsWithoutDemandingNetworkForSqlite() {
        val local = SavedConnection(name = "file", kind = DatabaseKind.SQLITE, database = "a".repeat(64))
        assertTrue(local.fieldErrors().isEmpty())
        val redis = SavedConnection(name = "keys", kind = DatabaseKind.REDIS, host = "localhost", port = "6379", database = "0", username = "default")
        assertTrue(redis.fieldErrors().isEmpty())
        assertTrue(redis.copy(database = "-1").fieldErrors().containsKey("database"))
        assertTrue(redis.copy(database = "65536").fieldErrors().containsKey("database"))
        assertTrue(redis.copy(host = "redis://localhost", port = "0").fieldErrors().keys.containsAll(listOf("host", "port")))
        assertTrue(redis.copy(kind = DatabaseKind.POSTGRESQL, database = "business").fieldErrors().isEmpty())
        assertTrue(redis.copy(kind = DatabaseKind.MONGODB, authDatabase = "").fieldErrors().containsKey("authDatabase"))
        val search = redis.copy(kind = DatabaseKind.OPENSEARCH, database = "logs-*")
        assertTrue(search.fieldErrors().isEmpty())
        assertTrue(search.copy(database = "../secret").fieldErrors().containsKey("database"))
        assertTrue(search.copy(username = "bad:user").fieldErrors().containsKey("username"))
        assertTrue(redis.copy(username = "", name = "").fieldErrors().keys.containsAll(listOf("username", "name")))
    }
}
