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
    val production: Boolean = false, val ca: String? = null) {
    fun profile(context: Context) = ConnectionProfile(DatabaseKind.SQL_SERVER, host.trim(), port.toIntOrNull() ?: 0,
        database.trim(), username, environment = if (production) ConnectionEnvironment.PRODUCTION else ConnectionEnvironment.TEST,
        trustStore = ca?.let { require(it.matches(Regex("[a-f0-9]{64}"))); TrustStoreSpec(File(context.filesDir, "ca/$it.p12").path) })
}
class ConnectionStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "connections-v1.json"))
    fun load(): List<SavedConnection> {
        if (!file.baseFile.exists()) return emptyList()
        val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(root.getInt("schema") == 1)
        val rows = root.getJSONArray("connections")
        return (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
            SavedConnection(row.getString("id"), row.getString("name"), row.getString("host"), row.getString("port"),
                row.getString("database"), row.getString("username"), row.getBoolean("production"),
                row.optString("ca").ifEmpty { null })
        } }
    }
    fun save(connections: List<SavedConnection>) {
        require(connections.size <= 100)
        val rows = JSONArray()
        connections.forEach { c -> rows.put(JSONObject().apply {
            put("id", c.id); put("name", c.name); put("host", c.host); put("port", c.port)
            put("database", c.database); put("username", c.username); put("production", c.production); put("ca", c.ca ?: "")
        }) }
        val out = file.startWrite()
        try { out.write(JSONObject().put("schema", 1).put("connections", rows).toString().toByteArray()); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
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
