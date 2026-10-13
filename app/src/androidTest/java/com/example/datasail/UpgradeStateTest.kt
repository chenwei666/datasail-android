package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Invoked explicitly before/after install -r on a disposable emulator only. */
class UpgradeStateSeedTest {
    @Test fun seedPreviousVersionPasswordAndSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("0.8.1", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val connection = ConnectionStore(context).load().single { it.id == "upgrade-fixture" }
        val secret = "synthetic-upgrade-only".toCharArray()
        try { PasswordVault(context).save(connection, secret) } finally { secret.fill('\u0000') }
        context.getSharedPreferences("workspace-settings-v1", 0).edit().putInt("gridFont", 18).putBoolean("defaultProduction", true).commit()
        TableFavoriteStore(context).save(listOf(TableFavorite(tableConnectionKey(connection), connection.name, connection.kind, "synthetic", "dbo", "upgrade_table", false)))
    }
}
class UpgradeStateVerifyTest {
    @Test fun passwordAndSettingsSurviveInPlaceUpgrade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("0.9.0", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val connection = ConnectionStore(context).load().single { it.id == "upgrade-fixture" }
        val secret = PasswordVault(context).load(connection)
        try { assertArrayEquals("synthetic-upgrade-only".toCharArray(), secret) } finally { secret?.fill('\u0000') }
        assertEquals(18, AppSettingsStore(context).load().gridFont)
        assertTrue(AppSettingsStore(context).load().defaultProduction)
        assertFalse(connection.allowExternalAi)
        assertEquals("upgrade_table", TableFavoriteStore(context).load().single().name)
        assertFalse(AiStore(context).load().enabled)
        assertEquals(200, AppSettingsStore(context).load().columnWidth)
        // Restore defaults only for the synthetic test suite that follows.
        AppSettingsStore(context).save(AppSettings())
    }
}
