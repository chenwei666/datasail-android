package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.ConnectionEnvironment
import org.junit.Assert.*
import org.junit.Test

/** Explicit previous-version installation fixture, excluded from default connected tests. */
class UpgradeTest {
    @Test fun previousVersionConnectionAndProductionPolicySurviveUpgrade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = ConnectionStore(context).load().single { it.id == "upgrade-fixture" }
        assertEquals("升级保留连接", saved.name)
        assertEquals("localhost", saved.host)
        assertEquals("synthetic", saved.database)
        assertEquals("fixture", saved.username)
        assertEquals("1433", saved.port)
        assertEquals(com.example.datasail.core.DatabaseKind.SQL_SERVER, saved.kind)
        assertNull(saved.ca)
        assertEquals("a".repeat(64), saved.certificateTrust!!.sha256)
        assertTrue(saved.certificateTrust!!.matches(saved))
        assertEquals(123L, saved.certificateTrust!!.confirmedAt)
        assertEquals("admin", saved.authDatabase)
        val favorite = QueryStore(context).load().single { it.id == "upgrade-query" }
        assertEquals("SELECT 1", favorite.sql)
        assertEquals(com.example.datasail.core.DatabaseKind.POSTGRESQL, favorite.kind)
        val mongo = ConnectionStore(context).load().single { it.id == "upgrade-mongo" }
        assertEquals("custom_auth", mongo.authDatabase)
        assertEquals(com.example.datasail.core.DatabaseKind.MONGODB, mongo.kind)
        val search = ConnectionStore(context).load().single { it.id == "upgrade-search" }
        assertEquals(com.example.datasail.core.DatabaseKind.OPENSEARCH, search.kind)
        assertEquals("logs-*", search.database)
        assertTrue(search.production)
        assertEquals(ConnectionEnvironment.PRODUCTION, saved.profile(context).environment)
    }
}
