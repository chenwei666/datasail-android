package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PasswordVaultTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun encryptedRoundTripBindsEndpointIdentityAndCopies() {
        val vault = PasswordVault(context)
        val connection = SavedConnection(name = "密码合成夹具", host = "localhost", username = "fixture")
        val secret = "synthetic-密码-0007".toCharArray()
        try {
            vault.save(connection, secret)
            assertArrayEquals(secret, PasswordVault(context).load(connection))
            assertArrayEquals(secret, vault.load(connection.copy(host = "LOCALHOST", database = "other")))
            for (changed in listOf(connection.copy(host = "other"), connection.copy(port = "1434"), connection.copy(username = "another"), connection.copy(authDatabase = "other"), connection.copy(id = "copy"), connection.copy(kind = com.example.datasail.core.DatabaseKind.MYSQL))) assertNull(vault.load(changed))
            val bytes = encryptedFile(connection).readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("synthetic-"))
            assertFalse(ConnectionStore(context).let { it.load().toString() }.contains("synthetic-密码-0007"))
            vault.delete(connection.id); assertNull(vault.load(connection))
        } finally { vault.delete(connection.id); secret.fill('\u0000') }
    }
    @Test fun tamperedCiphertextFailsClosedAndOriginalIsNotDeleted() {
        val vault = PasswordVault(context); val connection = SavedConnection(name = "损坏夹具", host = "localhost", username = "fixture")
        val password = "synthetic-secret".toCharArray()
        try {
            vault.save(connection, password)
            val file = encryptedFile(connection); val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertThrows(Exception::class.java) { vault.load(connection) }
            assertTrue(file.exists())
        } finally { vault.delete(connection.id); password.fill('\u0000') }
    }
    @Test fun settingsSurviveStoreRecreation() {
        val store = AppSettingsStore(context); val original = store.load()
        try {
            val changed = AppSettings(ThemeMode.DARK, false, 18, 16, false, false, true, true, 360)
            store.save(changed); assertEquals(changed, AppSettingsStore(context).load())
            store.save(AppSettings()); assertEquals(AppSettings(), store.load())
        } finally { store.save(original) }
    }
    private fun encryptedFile(connection: SavedConnection): File {
        val name = MessageDigest.getInstance("SHA-256").digest(connection.id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.noBackupFilesDir, "passwords-v1/$name.bin")
    }
}
