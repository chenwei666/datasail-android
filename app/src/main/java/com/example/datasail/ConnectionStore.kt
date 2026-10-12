package com.example.datasail

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.example.datasail.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID

/** Only metadata and explicitly imported public CA certificates; never passwords. */
data class SavedConnection(val id: String = UUID.randomUUID().toString(), val name: String = "",
    val host: String = "", val port: String = "1433", val database: String = "", val username: String = "",
    val production: Boolean = false, val ca: String? = null, val kind: DatabaseKind = DatabaseKind.SQL_SERVER,
    val authDatabase: String = "admin") {
    fun profile(context: Context) = ConnectionProfile(kind, host.trim(), port.toIntOrNull() ?: 0,
        database.trim(), username, environment = if (production) ConnectionEnvironment.PRODUCTION else ConnectionEnvironment.TEST,
        authDatabase = authDatabase.trim(), trustStore = ca?.let { require(it.matches(Regex("[a-f0-9]{64}"))); TrustStoreSpec(File(context.filesDir, "ca/$it.p12").path) })
}
class ConnectionStore(private val context: Context) {
    companion object { private val metadataLock = Any() }
    private val file = AtomicFile(File(context.filesDir, "connections-v1.json"))
    fun load(): List<SavedConnection> = synchronized(metadataLock) { loadLocked() }
    private fun loadLocked(): List<SavedConnection> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val root = file.openRead().use { input -> require(input.channel.size() <= 4000000); JSONObject(input.bufferedReader().readText()) }
        require(root.getInt("schema") in 1..3)
        val rows = root.getJSONArray("connections"); require(rows.length() <= 100)
        return (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
            SavedConnection(row.getString("id"), row.getString("name"), row.getString("host"), row.getString("port"),
                row.getString("database"), row.getString("username"), row.getBoolean("production"),
                row.optString("ca").ifEmpty { null },
                if (root.getInt("schema") == 1) DatabaseKind.SQL_SERVER else DatabaseKind.valueOf(row.getString("kind")), row.optString("authDatabase", "admin"))
        } }
    }
    fun save(connections: List<SavedConnection>) = synchronized(metadataLock) { saveLocked(connections) }
    private fun saveLocked(connections: List<SavedConnection>) {
        require(connections.size <= 100)
        val rows = JSONArray()
        connections.forEach { c -> rows.put(JSONObject().apply {
            put("authDatabase", c.authDatabase); put("kind", c.kind.name); put("id", c.id); put("name", c.name); put("host", c.host); put("port", c.port)
            put("database", c.database); put("username", c.username); put("production", c.production); put("ca", c.ca ?: "")
        }) }
        val encoded = JSONObject().put("schema", 3).put("connections", rows).toString().toByteArray()
        require(encoded.size <= 4000000)
        val out = file.startWrite()
        try { out.write(encoded); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
    fun sqliteFile(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return File(context.filesDir, "sqlite/$id.db")
    }
    fun importSqlite(uri: Uri): String {
        val dir = File(context.filesDir, "sqlite").apply { mkdirs() }
        val temp = File.createTempFile("import-", ".db", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= 64L * 1024 * 1024) { "SQLite copy exceeds limit" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            val header = temp.inputStream().use { it.readBytesLimitedHeader() }
            require(header.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray()))
            require(header[18].toInt() == 1 && header[19].toInt() == 1) { "Export a checkpointed rollback-journal copy first" }
            android.database.sqlite.SQLiteDatabase.openDatabase(temp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA quick_check(1)", null).use { require(it.moveToFirst() && it.getString(0) == "ok") }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = sqliteFile(id)
            if (!destination.exists()) require(temp.renameTo(destination))
            return id
        } finally { temp.delete() }
    }
    private fun java.io.InputStream.readBytesLimitedHeader(): ByteArray {
        val header = ByteArray(100); var count = 0
        while (count < header.size) { val n = read(header, count, header.size - count); require(n > 0); count += n }
        return header
    }
    fun importCa(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytesLimited(65536) }
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        cert.checkValidity(); require(cert.basicConstraints >= 0) { "CA required" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "ca").apply { mkdirs() }
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("approved-ca", cert) }
        val target = AtomicFile(File(dir, "$fingerprint.p12")); val out = target.startWrite()
        try { store.store(out, "changeit".toCharArray()); target.finishWrite(out) }
        catch (error: Exception) { target.failWrite(out); throw error }
        return fingerprint
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { val count = read(buffer); if (count < 0) break; require(output.size() + count <= limit); output.write(buffer, 0, count) }
        return output.toByteArray()
    }
}
