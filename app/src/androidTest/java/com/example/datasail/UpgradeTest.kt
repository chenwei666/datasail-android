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
        assertNull(saved.ca)
        assertEquals(ConnectionEnvironment.PRODUCTION, saved.profile(context).environment)
    }
}
