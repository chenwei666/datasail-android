package com.example.datasail

import com.example.datasail.core.DatabaseKind
import org.junit.Assert.*
import org.junit.Test

class ConnectionFormTest {
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
        assertTrue(redis.copy(username = "", name = "").fieldErrors().keys.containsAll(listOf("username", "name")))
    }
}
