package com.example.datasail.core

import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/** Search capability has no arbitrary endpoint, update, bulk, script or administrative API. */
interface SearchSession : AutoCloseable {
    fun indices(): List<String>
    fun mapping(index: String): String
    fun search(index: String, query: String, offset: Int = 0): SearchPage
    fun isClosed(): Boolean
}
data class SearchHit(val id: String, val json: String?, val unavailable: String? = null)
data class SearchPage(val index: String, val query: String, val offset: Int, val hits: List<SearchHit>, val hasMore: Boolean, val windowLimit: Boolean)
internal class SearchHttpFailure(val status: Int) : Exception("Search HTTP failure")

/** Platform HTTPS, strict CA + SAN, fixed origin, no redirects/proxy or retained HTTP pool. */
class HttpsSearchSession(private val profile: ConnectionProfile) : SearchSession {
    private val closed = AtomicBoolean(false)
    private val timer = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val context: ClosingTlsContext
    @Volatile private var active: HttpsURLConnection? = null
    @Volatile private var auth: String? = null
    private var knownIndices = emptySet<String>()
    init {
        require(profile.kind.isSearch)
        val trust = SqlServerTrustManager("${profile.host}\n${profile.trustStore?.type ?: "PKCS12"}\n${profile.trustStore?.path.orEmpty()}")
        context = ClosingTlsContext(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) })
    }
    fun login(credentials: Credentials) {
        try {
        credentials.use { owned ->
            require(!profile.username.contains(':') && profile.username.none { it < ' ' })
            val encoded = owned.usePassword { password ->
                val bytes = (profile.username + ":" + password).toByteArray(Charsets.UTF_8)
                try { "Basic " + Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
            }
            synchronized(this) { check(!closed.get()); auth = encoded }
        }
        val root = request("/").obj()
        val version = root["version"]?.obj() ?: throw WorkspaceRejected("无法识别搜索服务版本")
        val distribution = (version["distribution"] as? SearchJson.Str)?.value
        val matches = if (profile.kind == DatabaseKind.OPENSEARCH) distribution == "opensearch"
            else distribution == null && (root["tagline"] as? SearchJson.Str)?.value == "You Know, for Search"
        if (!matches) { close(); throw WorkspaceRejected("服务器类型与所选数据库不一致") }
        } catch (error: Exception) { close(); throw error }
    }
    override fun indices(): List<String> {
        val rows = request("/_cat/indices/${profile.database}?format=json&h=index&expand_wildcards=open") as? SearchJson.Arr
            ?: throw WorkspaceRejected("无法读取索引列表")
        if (rows.values.size > 200) throw WorkspaceRejected("索引超过 200 个，请缩小连接中的索引范围")
        val names = rows.values.map { it.obj()["index"]?.string() ?: throw WorkspaceRejected("索引名称缺失") }
        if (names.any { !SearchPolicy.validIndex(it) }) throw WorkspaceRejected("当前版本不支持此索引名称，请缩小索引范围")
        knownIndices = names.toSet(); return names.distinct().sorted()
    }
    override fun mapping(index: String): String {
        checkIndex(index)
        val result = request("/$index/_mapping").encode()
        if (result.length > 262_144) throw WorkspaceRejected("映射超过显示上限 256 Ki 字符")
        return result
    }
    override fun search(index: String, query: String, offset: Int): SearchPage {
        checkIndex(index)
        if (offset !in 0..900 || offset % 100 != 0) throw WorkspaceRejected("最多浏览前 1000 条，请缩小查询范围")
        val condition = SearchPolicy.query(query)
        val body = "{\"query\":${condition.encode()},\"from\":$offset,\"size\":101,\"sort\":[\"_doc\"],\"timeout\":\"8s\",\"track_total_hits\":false}"
        val root = request("/$index/_search?allow_partial_search_results=false", body).obj()
        if ((root["timed_out"] as? SearchJson.Atom)?.raw != "false" ||
            (root["_shards"]?.obj()?.get("failed") as? SearchJson.Atom)?.raw != "0")
            throw WorkspaceRejected("搜索超时或分片失败，未显示不完整结果")
        val hits = root["hits"]?.obj()?.get("hits") as? SearchJson.Arr ?: throw WorkspaceRejected("搜索结果结构不受支持")
        if (hits.values.size > 101) throw WorkspaceRejected("服务器返回超过请求上限的结果")
        var remaining = 262_144
        val previews = hits.values.take(100).map { hit ->
            val fields = hit.obj(); val id = fields["_id"]?.string() ?: throw WorkspaceRejected("搜索结果缺少 ID")
            if (id.length > 1024) throw WorkspaceRejected("搜索结果 ID 超限")
            val source = fields["_source"]?.encode()
            if (source == null) SearchHit(id, null, "此文档未提供 _source")
            else if (source.length > 8192 || source.length > remaining) SearchHit(id, null, "文档超过单条或页面显示上限，未展开")
            else { remaining -= source.length; SearchHit(id, source) }
        }
        return SearchPage(index, query, offset, previews, hits.values.size > 100 && offset < 900, hits.values.size > 100 && offset == 900)
    }
    private fun checkIndex(index: String) { if (index !in knownIndices || !SearchPolicy.validIndex(index)) throw WorkspaceRejected("请从当前连接的索引列表选择索引") }
    private fun request(path: String, body: String? = null): SearchJson {
        check(!closed.get())
        val expired = AtomicBoolean(false)
        val deadline = timer.schedule({ expired.set(true); close() }, 12, TimeUnit.SECONDS)
        var connection: HttpsURLConnection? = null
        try {
            connection = URL("https", profile.host, profile.port, path).openConnection(Proxy.NO_PROXY) as HttpsURLConnection
            connection.sslSocketFactory = context.socketFactory
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", auth ?: throw IllegalStateException("Not authenticated"))
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            synchronized(this) { if (closed.get()) throw java.net.SocketException("Cancelled"); active = connection }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw SearchHttpFailure(status)
            if (connection.contentEncoding?.let { !it.equals("identity", true) } == true) throw WorkspaceRejected("不支持压缩响应")
            if (connection.contentLengthLong > 2_097_152) throw WorkspaceRejected("响应超过 2 MiB，请缩小查询范围")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > 2_097_152) throw WorkspaceRejected("响应超过 2 MiB，请缩小查询范围")
                    output.write(buffer, 0, count)
                }
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
            val result = SearchJson.parse(text)
            if (closed.get()) throw java.net.SocketException("Cancelled")
            return result
        } catch (error: Exception) {
            close()
            if (expired.get()) throw SocketTimeoutException("Search deadline exceeded")
            throw error
        } finally { deadline.cancel(false); connection?.disconnect(); active = null }
    }
    override fun isClosed() = closed.get()
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(this) { auth = null }; context.close(); active?.disconnect(); timer.shutdownNow()
        }
    }
}
