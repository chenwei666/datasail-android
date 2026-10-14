package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Invoked explicitly before/after install -r on a disposable emulator only. */
class UpgradeStateSeedTest {
    @Test fun seedPreviousVersionPasswordAndSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("0.12.0", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val ai = AiProfile(id = "upgrade-ai", name = "升级 AI", endpoint = "https://example.com/v1", model = "fixture-model")
        AiStore(context).save(AiPreferences(true, ai.id, listOf(ai)))
        PasswordVault(context, "ai-keys-v1").saveBound(ai.id, ai.binding, "synthetic-ai-upgrade-only".toCharArray())
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
        assertEquals("0.12.1", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val connection = ConnectionStore(context).load().single { it.id == "upgrade-fixture" }
        val secret = PasswordVault(context).load(connection)
        try { assertArrayEquals("synthetic-upgrade-only".toCharArray(), secret) } finally { secret?.fill('\u0000') }
        assertEquals(18, AppSettingsStore(context).load().gridFont)
        assertTrue(AppSettingsStore(context).load().defaultProduction)
        assertFalse(connection.allowExternalAi)
        assertEquals("upgrade_table", TableFavoriteStore(context).load().single().name)
        val ai = AiStore(context).load()
        assertTrue(ai.enabled)
        assertEquals("fixture-model", ai.active!!.model)
        val aiSecret = PasswordVault(context, "ai-keys-v1").loadBound(ai.active!!.id, ai.active!!.binding)
        try { assertArrayEquals("synthetic-ai-upgrade-only".toCharArray(), aiSecret) } finally { aiSecret?.fill('\u0000') }
        PasswordVault(context, "ai-keys-v1").delete(ai.active!!.id)
        AiStore(context).save(AiPreferences())
        assertEquals(200, AppSettingsStore(context).load().columnWidth)
        // Restore defaults only for the synthetic test suite that follows.
        AppSettingsStore(context).save(AppSettings())
    }
}
