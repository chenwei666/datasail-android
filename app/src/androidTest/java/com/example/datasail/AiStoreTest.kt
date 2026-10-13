package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.AiProtocol
import org.junit.Assert.*
import org.junit.Test

class AiStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun aiKeysAreIsolatedByEndpointProtocolAndDatabaseVault() {
        val profile = AiProfile(name = "合成 AI", endpoint = "https://localhost/v1")
        val vault = PasswordVault(context, "ai-keys-v1"); val key = "synthetic-ai-only".toCharArray()
        try {
            vault.saveBound(profile.id, profile.binding, key)
            assertArrayEquals(key, PasswordVault(context, "ai-keys-v1").loadBound(profile.id, profile.binding))
            assertNull(vault.loadBound(profile.id, profile.copy(endpoint = "https://other.example/v1").binding))
            assertNull(vault.loadBound(profile.id, profile.copy(protocol = AiProtocol.ANTHROPIC).binding))
            assertNull(PasswordVault(context).loadBound(profile.id, profile.binding))
        } finally { vault.delete(profile.id); key.fill('\u0000') }
    }
    @Test fun profilesAndDisabledPolicyRoundTripWithoutSecrets() {
        val store = AiStore(context); val original = store.load()
        val connections = ConnectionStore(context); val before = connections.load()
        try {
            val profile = AiProfile(model = "synthetic-model")
            val preferences = AiPreferences(true, profile.id, listOf(profile))
            store.save(preferences); assertEquals(preferences, AiStore(context).load())
            assertFalse(java.io.File(context.filesDir, "ai-services-v1.json").readText().contains("key"))
            val denied = SavedConnection(host = "localhost", username = "fixture", database = "synthetic")
            assertFalse(denied.allowExternalAi)
            connections.save(listOf(denied, denied.copy(id = "allowed", allowExternalAi = true)))
            assertEquals(listOf(false,true), connections.load().map { it.allowExternalAi })
        } finally { store.save(original); connections.save(before) }
    }
}
