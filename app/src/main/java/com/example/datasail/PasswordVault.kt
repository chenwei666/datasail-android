package com.example.datasail

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local AES-GCM ciphertext only; credentials never enter connection metadata or backup. */
internal class PasswordVault(context: Context) {
    private val directory = File(context.noBackupFilesDir, "passwords-v1")
    companion object {
        private val lock = Any()
        private const val ALIAS = "DataSail.passwords.v1"
        internal fun binding(connection: SavedConnection): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                listOf(connection.id, connection.kind.name, connection.host.trim().lowercase(java.util.Locale.ROOT),
                    connection.port.toIntOrNull()?.toString().orEmpty(), connection.username, connection.authDatabase.trim()).forEach(out::writeUTF)
            }; bytes.toByteArray()
        }
    }
    private fun file(id: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.bin"))
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Saved password key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun save(connection: SavedConnection, password: CharArray) = synchronized(lock) {
        require(password.isNotEmpty() && password.size <= 4096)
        check(directory.isDirectory || directory.mkdirs())
        val aad = binding(connection)
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password))
        val plain = ByteArray(encoded.remaining()).also { encoded.get(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plain)
            require(cipher.iv.size == 12)
            val bytes = byteArrayOf(1) + MessageDigest.getInstance("SHA-256").digest(aad) + cipher.iv + encrypted
            val target = file(connection.id); val stream = target.startWrite()
            try { stream.write(bytes); target.finishWrite(stream) } catch (error: Exception) { target.failWrite(stream); throw error }
        } finally { plain.fill(0); if (encoded.hasArray()) encoded.array().fill(0) }
    }
    fun load(connection: SavedConnection): CharArray? = synchronized(lock) {
        val target = file(connection.id)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return@synchronized null
        val bytes = target.openRead().use { require(it.channel.size() in 61..20000); it.readBytes() }
        require(bytes[0] == 1.toByte())
        val aad = binding(connection)
        if (!MessageDigest.isEqual(bytes.copyOfRange(1, 33), MessageDigest.getInstance("SHA-256").digest(aad))) return@synchronized null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(33, 45))); cipher.updateAAD(aad)
        val plain = cipher.doFinal(bytes.copyOfRange(45, bytes.size))
        try {
            val decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain))
            CharArray(decoded.remaining()).also { decoded.get(it); if (decoded.hasArray()) decoded.array().fill('\u0000') }
        } finally { plain.fill(0) }
    }
    fun delete(id: String) = synchronized(lock) { file(id).delete() }
    fun clear() = synchronized(lock) {
        directory.listFiles()?.forEach { check(it.delete()) }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(ALIAS)) deleteEntry(ALIAS) }
    }
    fun count(): Int = synchronized(lock) { directory.listFiles()?.count { it.name.endsWith(".bin") } ?: 0 }
}
