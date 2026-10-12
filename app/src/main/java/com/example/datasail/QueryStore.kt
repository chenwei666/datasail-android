package com.example.datasail

import android.content.Context
import android.util.AtomicFile
import com.example.datasail.core.DatabaseKind
import com.example.datasail.core.ReadQueryPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedQuery(val id: String = UUID.randomUUID().toString(), val name: String, val kind: DatabaseKind, val sql: String)
class QueryStore(context: Context) {
    companion object { private val metadataLock = Any() }
    private val file = AtomicFile(File(context.filesDir, "queries-v1.json"))
    fun load(): List<SavedQuery> = synchronized(metadataLock) { loadLocked() }
    private fun loadLocked(): List<SavedQuery> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val root = file.openRead().use { input -> require(input.channel.size() <= 8000000); JSONObject(input.bufferedReader().readText()) }; require(root.getInt("schema") == 1)
        val rows = root.getJSONArray("queries"); require(rows.length() <= 100)
        return (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            SavedQuery(it.getString("id"), it.getString("name"), DatabaseKind.valueOf(it.getString("kind")), it.getString("sql"))
        } }
    }
    fun save(values: List<SavedQuery>) = synchronized(metadataLock) { saveLocked(values) }
    private fun saveLocked(values: List<SavedQuery>) {
        require(values.size <= 100)
        val rows = JSONArray()
        values.forEach { query ->
            require(query.name.isNotBlank() && query.name.length <= 80 && query.kind.isSql)
            ReadQueryPolicy.validate(query.sql, query.kind)
            rows.put(JSONObject().put("id", query.id).put("name", query.name).put("kind", query.kind.name).put("sql", query.sql))
        }
        val encoded = JSONObject().put("schema", 1).put("queries", rows).toString().toByteArray()
        require(encoded.size <= 8000000)
        val out = file.startWrite()
        try { out.write(encoded); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
}
