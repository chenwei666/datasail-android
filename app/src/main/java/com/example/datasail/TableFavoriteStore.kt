package com.example.datasail

import android.content.Context
import android.util.AtomicFile
import com.example.datasail.core.DatabaseKind
import com.example.datasail.core.TableRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// No password, certificate or table rows are retained. Endpoint identity prevents retargeted connections.
internal fun tableConnectionKey(connection: SavedConnection): String {
    val parts = listOf(connection.id, connection.kind.name, connection.host.trim().lowercase(), connection.port,
        connection.username, connection.authDatabase, if (connection.kind == DatabaseKind.SQLITE) connection.database else "")
    val encoded = parts.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
}
data class TableFavorite(val connectionKey: String, val connectionName: String, val kind: DatabaseKind,
    val catalog: String, val schema: String, val name: String, val view: Boolean) {
    fun matches(key: String, table: TableRef) = connectionKey == key && catalog == table.catalog &&
        schema == table.schema && name == table.name && view == table.view
}
class TableFavoriteStore(context: Context) {
    companion object { private val lock = Any() }
    private val file = AtomicFile(File(context.filesDir, "table-favorites-v1.json"))
    fun load(): List<TableFavorite> = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val root = file.openRead().use { require(it.channel.size() <= 1000000); JSONObject(it.bufferedReader().readText()) }
        require(root.getInt("schema") == 1)
        val rows = root.getJSONArray("tables"); require(rows.length() <= 100)
        (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            TableFavorite(it.getString("connectionKey"), it.getString("connectionName"), DatabaseKind.valueOf(it.getString("kind")),
                it.getString("catalog"), it.getString("schema"), it.getString("name"), it.getBoolean("view"))
        } }.also(::validate)
    }
    private fun validate(values: List<TableFavorite>) {
        require(values.size <= 100 && values.distinct().size == values.size)
        values.forEach { require(it.kind.isSql && it.connectionKey.matches(Regex("[0-9a-f]{64}")))
            require(listOf(it.connectionName, it.catalog, it.schema, it.name).all { name -> name.isNotBlank() && name.length <= 512 }) }
    }
    fun save(values: List<TableFavorite>) = synchronized(lock) {
        validate(values)
        val rows = JSONArray()
        values.forEach { rows.put(JSONObject().put("connectionKey", it.connectionKey).put("connectionName", it.connectionName)
            .put("kind", it.kind.name).put("catalog", it.catalog).put("schema", it.schema).put("name", it.name).put("view", it.view)) }
        val bytes = JSONObject().put("schema", 1).put("tables", rows).toString().toByteArray()
        require(bytes.size <= 1000000)
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (error: Exception) { file.failWrite(out); throw error }
    }
}
