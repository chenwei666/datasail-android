package com.example.datasail.core

import com.mongodb.*
import com.mongodb.client.*
import com.mongodb.connection.ClusterConnectionMode
import org.bson.*
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLContext

/** Immutable display values: canonical Extended JSON preserves BSON types; no mutation capability. */
data class DocumentPreview(val json: String?, val unavailable: String? = null)
data class DocumentPage(val collection: String, val filter: String, val documents: List<DocumentPreview>, val hasMore: Boolean, val page: Int)
interface DocumentSession : AutoCloseable {
    fun collections(): List<String>
    fun find(collection: String, filter: String): DocumentPage
    fun next(): DocumentPage
    fun isClosed(): Boolean
}

object DocumentFilter {
    private val operators = setOf("\$eq", "\$ne", "\$gt", "\$gte", "\$lt", "\$lte", "\$in", "\$nin", "\$exists", "\$and", "\$or", "\$nor", "\$not")
    fun parse(text: String): BsonDocument {
        if (text.length !in 2..8000) throw WorkspaceRejected("筛选需为 2–8000 字的 JSON 对象")
        // Check nesting before the recursive driver parser, including malicious/malformed input.
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { c ->
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; if (depth > 24) throw WorkspaceRejected("筛选嵌套过深") }; '}', ']' -> { depth--; if (depth < 0) throw WorkspaceRejected("筛选括号无效") }; '\'', '(', ')' -> throw WorkspaceRejected("请使用标准 JSON 和 Extended JSON 类型对象") }
        }
        if (depth != 0 || quoted) throw WorkspaceRejected("筛选 JSON 不完整")
        val value = try { BsonDocument.parse(text) } catch (_: Exception) { throw WorkspaceRejected("筛选 JSON 或 BSON 类型格式无效") }
        var nodes = 0
        fun visit(v: BsonValue, level: Int) {
            if (++nodes > 1000 || level > 24) throw WorkspaceRejected("筛选过于复杂")
            when {
                v.isDocument -> v.asDocument().forEach { (key, child) ->
                    if (key.startsWith("$") && key !in operators) throw WorkspaceRejected("筛选只支持比较、包含、存在及逻辑操作符；不运行脚本或表达式")
                    visit(child, level + 1)
                }
                v.isArray -> v.asArray().forEach { visit(it, level + 1) }
                v.bsonType in setOf(BsonType.JAVASCRIPT, BsonType.JAVASCRIPT_WITH_SCOPE, BsonType.REGULAR_EXPRESSION) -> throw WorkspaceRejected("不支持脚本或正则筛选")
            }
        }
        visit(value, 0); return value
    }
}

class MongoSession(private val profile: ConnectionProfile) : DocumentSession {
    private val stopped = AtomicBoolean(false)
    private val tlsFailure = java.util.concurrent.atomic.AtomicReference<java.security.cert.CertificateException?>()
    private val sockets = ClosingTlsContext(SSLContext.getInstance("TLS").apply {
        val strict = SqlServerTrustManager(CertificateTrust.configuration(profile))
        val tracking = object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers() = strict.acceptedIssuers
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, auth: String) = strict.checkClientTrusted(chain, auth)
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, auth: String) {
                try { strict.checkServerTrusted(chain, auth) }
                catch (error: java.security.cert.CertificateException) { tlsFailure.compareAndSet(null, error); throw error }
            }
        }
        init(null, arrayOf(tracking), null)
    })
    private val timer = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    @Volatile private var client: MongoClient? = null
    private var cursor: MongoCursor<RawBsonDocument>? = null
    private var knownCollections: Set<String> = emptySet()
    private var collection = ""
    private var filter = "{}"
    private var page = 0
    init { require(profile.kind == DatabaseKind.MONGODB) }
    fun login(credentials: Credentials) = bounded(30) {
        // The driver otherwise logs server addresses and exception text via its JUL fallback.
        Logger.getLogger("org.mongodb.driver").level = Level.OFF
        val settings = credentials.use { it.usePassword { password ->
            val chars = password.toCharArray()
            try { MongoClientSettings.builder()
                .credential(MongoCredential.createScramSha256Credential(profile.username, profile.authDatabase, chars))
                .applyToClusterSettings { b -> b.hosts(listOf(ServerAddress(profile.host, profile.port))).mode(ClusterConnectionMode.SINGLE).serverSelectionTimeout(15, TimeUnit.SECONDS) }
                .applyToSslSettings { b -> b.enabled(true).invalidHostNameAllowed(profile.certificatePin != null).context(sockets) }
                .applyToSocketSettings { b -> b.connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS) }
                .applyToConnectionPoolSettings { b -> b.maxSize(1).minSize(0).maxConnecting(1) }
                .timeout(30, TimeUnit.SECONDS).retryReads(false).retryWrites(false).applicationName("DataSail").build()
            } finally { chars.fill('\u0000') }
        } }
        val fresh = MongoClients.create(settings)
        synchronized(this) { if (stopped.get()) { fresh.close(); throw java.net.SocketException("Cancelled") }; client = fresh }
        fresh.getDatabase(profile.database).runCommand(BsonDocument("ping", BsonInt32(1)))
        Unit
    }
    fun databases(): List<String> = bounded {
        val names = ArrayList<String>()
        (client ?: throw WorkspaceRejected("请先连接服务器")).withTimeout(10, TimeUnit.SECONDS)
            .listDatabases(BsonDocument::class.java).nameOnly(true).authorizedDatabasesOnly(true)
            .maxTime(8, TimeUnit.SECONDS).iterator().use { cursor ->
                while (cursor.hasNext() && names.size <= DiscoveredDatabases.LIMIT) names += cursor.next().getString("name").value
            }
        names
    }
    override fun collections(): List<String> = bounded {
        val names = ArrayList<String>()
        database().listCollectionNames().batchSize(50).maxTime(8, TimeUnit.SECONDS).iterator().use { c ->
            while (c.hasNext()) {
                if (names.size >= 200) throw WorkspaceRejected("当前库集合超过 200 个，本版不截断集合列表；请使用范围更小的数据库")
                names += c.next()
            }
        }
        knownCollections = names.toSet(); names.sorted()
    }
    override fun find(collection: String, filter: String): DocumentPage {
        val parsed = DocumentFilter.parse(filter)
        if (collection !in knownCollections) throw WorkspaceRejected("请从已读取的集合列表选择")
        return bounded {
            cursor?.close(); cursor = null
            this.collection = collection; this.filter = filter; page = 0
            // Small wire batches avoid a round trip per document. Driver/server message buffers have separate limits.
            cursor = database().getCollection(collection, RawBsonDocument::class.java).find(parsed)
                .batchSize(25).timeoutMode(com.mongodb.client.cursor.TimeoutMode.ITERATION)
                .maxTime(8, TimeUnit.SECONDS).iterator()
            readPage()
        }
    }
    override fun next(): DocumentPage = bounded { readPage() }
    private fun readPage(): DocumentPage {
        val c = cursor ?: throw WorkspaceRejected("请重新打开集合")
        val result = ArrayList<DocumentPreview>(); var budget = 262144
        while (result.size < 100 && c.hasNext()) {
            val raw = c.next()
            val preview = preview(raw)
            if (preview.json != null && preview.json.length > budget) {
                result += DocumentPreview(null, "本页显示预算已用完，文档未展开")
            } else { result += preview; budget -= preview.json?.length ?: 0 }
        }
        val more = c.hasNext()
        if (!more) { c.close(); cursor = null }
        return DocumentPage(collection, filter, result, more, ++page)
    }
    private fun database() = (client ?: throw WorkspaceRejected("请先连接数据库")).getDatabase(profile.database).withTimeout(10, TimeUnit.SECONDS)
    private fun <T> bounded(seconds: Long = 12, block: () -> T): T {
        check(!stopped.get()) { "Session closed" }
        val expiry = timer.schedule({ close() }, seconds, TimeUnit.SECONDS)
        try { val result = block(); if (stopped.get()) throw java.net.SocketTimeoutException(); return result }
        catch (error: Exception) {
            close()
            // Server selection can discard handshake causes. Preserve the category without parsing/logging exception text.
            tlsFailure.get()?.let { throw javax.net.ssl.SSLHandshakeException("Certificate verification failed").apply { initCause(it) } }
            throw error
        }
        finally { expiry.cancel(false) }
    }
    override fun isClosed() = stopped.get()
    override fun close() {
        if (stopped.compareAndSet(false, true)) { sockets.close(); timer.shutdownNow(); client?.close() }
    }
    companion object {
        private val jsonSettings = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).indent(true).build()
        internal fun preview(raw: RawBsonDocument): DocumentPreview {
            if (raw.byteBuffer.remaining() > 32768) return DocumentPreview(null, "文档超过 32 KiB，未展开")
            var nodes = 0
            fun visit(v: BsonValue, depth: Int) {
                require(++nodes <= 2048 && depth <= 24)
                if (v.isDocument) v.asDocument().values.forEach { visit(it, depth + 1) }
                else if (v.isArray) v.asArray().forEach { visit(it, depth + 1) }
            }
            return try {
                visit(raw, 0)
                val json = raw.toJson(jsonSettings)
                if (json.length > 8192) DocumentPreview(null, "文档 JSON 超过 8192 字，未展开") else DocumentPreview(json)
            } catch (_: IllegalArgumentException) { DocumentPreview(null, "文档嵌套或字段数量超过预览上限") }
        }
    }
}
