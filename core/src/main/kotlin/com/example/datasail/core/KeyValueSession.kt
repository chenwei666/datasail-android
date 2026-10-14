package com.example.datasail.core

import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Byte identity is retained even for keys that are not UTF-8. */
class BinaryKey(bytes: ByteArray) {
    private val data = bytes.copyOf()
    fun bytes() = data.copyOf()
    val display: String get() = binaryLabel(data)
    override fun equals(other: Any?) = other is BinaryKey && data.contentEquals(other.data)
    override fun hashCode() = data.contentHashCode()
    override fun toString() = "BinaryKey([REDACTED])"
}
fun binaryLabel(bytes: ByteArray): String = try {
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    if (text.any { it.isISOControl() }) "hex:" + bytes.joinToString("") { "%02x".format(it) } else text.ifEmpty { "（空字节串）" }
} catch (_: Exception) { "hex:" + bytes.joinToString("") { "%02x".format(it) } }
data class KeyScan(val cursor: String, val keys: List<BinaryKey>, val pattern: String)
data class KeyContent(val key: BinaryKey, val type: String, val ttlMillis: Long, val length: Long,
    val rows: List<List<String>>, val cursor: String = "0", val limited: Boolean = false, val textValue: String? = null)
interface KeyValueSession : AutoCloseable, RecordWrites {
    fun scan(cursor: String = "0", pattern: String = "*"): KeyScan
    fun inspect(key: BinaryKey, cursor: String = "0"): KeyContent
    fun isClosed(): Boolean
}

/** RESP2 only, fixed read commands, strict CA + SAN validation; no script/command console or retries. */
class RespKeyValueSession(private val profile: ConnectionProfile) : KeyValueSession {
    private val raw = Socket()
    @Volatile private var socket: Socket = raw
    private val timer = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "datasail-resp-deadline").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    private val closed = AtomicBoolean(false)
    private var reader: RespReader? = null
    private var output: OutputStream? = null
    private var pending: Pair<MutationPreview, RecordDraft>? = null
    private var inspected: KeyContent? = null

    fun login(credentials: Credentials, selectDatabase: Boolean = true) {
        require(profile.kind.isKeyValue)
        try { bounded(30) {
            raw.connect(InetSocketAddress(profile.host, profile.port), 15000)
            raw.soTimeout = 15000
            val context = SSLContext.getInstance("TLS").apply { init(null,
                arrayOf(SqlServerTrustManager(CertificateTrust.configuration(profile))), null) }
            val tls = context.socketFactory.createSocket(raw, profile.host, profile.port, true) as SSLSocket
            socket = tls
            if (closed.get()) { tls.close(); throw SocketException("Closed") }
            tls.soTimeout = 10000
            tls.startHandshake()
            reader = RespReader(BufferedInputStream(tls.inputStream))
            output = BufferedOutputStream(tls.outputStream)
            credentials.usePassword { password -> command("AUTH", profile.username, password).expectOk() }
            if (selectDatabase) command("SELECT", profile.database).expectOk()
            check(command("PING").text() == "PONG")
        } } catch (error: Exception) { close(); throw error } finally { credentials.close() }
    }
    fun databases(): List<String> = bounded {
        command("INFO", "keyspace").text().lineSequence().filter { it.startsWith("db") }.map { line ->
            val id = line.substringBefore(':').removePrefix("db")
            require(id.matches(Regex("0|[1-9][0-9]{0,4}")) && id.toInt() <= 65535)
            id
        }.take(DiscoveredDatabases.LIMIT + 1).toList()
    }
    override fun scan(cursor: String, pattern: String): KeyScan = bounded {
        pending = null
        validCursor(cursor)
        require(pattern.toByteArray(Charsets.UTF_8).size <= 1024) { "Pattern too long" }
        val response = command("SCAN", cursor, "MATCH", pattern, "COUNT", "100").array()
        require(response.size == 2)
        val next = response[0].text().also(::validCursor)
        val keys = response[1].array().map { BinaryKey(it.bytes()) }
        // COUNT is a hint; never silently drop keys while advancing the server cursor.
        require(keys.size <= 1000) { "Scan page exceeds limit" }
        KeyScan(next, keys.distinct(), pattern)
    }
    override fun inspect(key: BinaryKey, cursor: String): KeyContent = bounded {
        validCursor(cursor)
        val type = keyCommand("TYPE", key).text()
        val ttl = keyCommand("PTTL", key).integer()
        var next = "0"
        var limited = false
        var textValue: String? = null
        val length: Long
        val rows: List<List<String>>
        when (type) {
            "none" -> { length = 0; rows = emptyList() }
            "string" -> {
                length = keyCommand("STRLEN", key).integer()
                val value = keyCommand("GETRANGE", key, "0", "2047").bytes()
                rows = listOf(listOf(binaryLabel(value))); limited = length > value.size
                if (!limited) textValue = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString() }.getOrNull()
            }
            "list" -> {
                length = keyCommand("LLEN", key).integer()
                rows = keyCommand("LRANGE", key, "0", "99").array().map { listOf(binaryLabel(it.bytes())) }
                limited = length > rows.size
            }
            "hash", "set" -> {
                length = keyCommand(if (type == "hash") "HLEN" else "SCARD", key).integer()
                val result = keyCommand(if (type == "hash") "HSCAN" else "SSCAN", key, cursor, "COUNT", "50").array()
                require(result.size == 2)
                next = result[0].text().also(::validCursor)
                val values = result[1].array().map { binaryLabel(it.bytes()) }
                if (type == "hash") require(values.size % 2 == 0)
                rows = values.chunked(if (type == "hash") 2 else 1)
            }
            "zset" -> {
                length = keyCommand("ZCARD", key).integer()
                val values = keyCommand("ZRANGE", key, "0", "99", "WITHSCORES").array().map { binaryLabel(it.bytes()) }
                require(values.size % 2 == 0); rows = values.chunked(2); limited = length > rows.size
            }
            "stream" -> {
                length = keyCommand("XLEN", key).integer()
                rows = keyCommand("XRANGE", key, "-", "+", "COUNT", "100").array().map { entry ->
                    val fields = entry.array(); require(fields.size == 2)
                    listOf(fields[0].text()) + fields[1].array().map { binaryLabel(it.bytes()) }
                }
                limited = length > rows.size
            }
            else -> throw WorkspaceRejected("此键类型尚未支持内容预览")
        }
        KeyContent(key, type, ttl, length, rows, next, limited, textValue).also { inspected = it; pending = null }
    }
    override fun discardRecord() { pending = null }
    override fun previewRecord(draft: RecordDraft): MutationPreview {
        pending = null; requireRecord(draft)
        if (draft.namespace != profile.database || draft.identity.isEmpty() || draft.replacement.toByteArray().size > 8192) throw WorkspaceRejected("键或值超出编辑范围")
        if (draft.kind != MutationKind.INSERT) {
            val content = inspected ?: throw WorkspaceRejected("请先读取键")
            if (!content.key.bytes().contentEquals(draft.identity.toByteArray()) || content.type != "string" || content.limited || content.textValue != draft.original)
                throw WorkspaceRejected("本版仅编辑完整加载的 UTF-8 字符串键")
        }
        return bounded {
            val value = command("GET", draft.identity)
            val old = if (value == Resp.Nil) null else Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value.bytes())).toString()
            if (old != draft.original || (draft.kind == MutationKind.INSERT && old != null)) throw WorkspaceRejected("键已变化，请刷新")
            recordPreview(draft, "${profile.kind.label} / ${profile.database} / ${draft.identity}", if (draft.kind == MutationKind.DELETE) "WATCH → 校验原值 → MULTI / DEL / EXEC" else "WATCH → 校验原值 → MULTI / SET ${if (draft.kind == MutationKind.INSERT) "NX" else "XX KEEPTTL"} / EXEC")
                .also { pending = it to draft }
        }
    }
    override fun confirmRecord(token: String): MutationResult {
        val (preview, draft) = pending ?: throw WorkspaceRejected("确认已失效")
        pending = null; requireToken(preview, token)
        var executing = false
        return try { bounded {
            command("WATCH", draft.identity).expectOk()
            val current = command("GET", draft.identity)
            val matches = if (draft.original == null) current == Resp.Nil else current is Resp.Bytes && current.value.contentEquals(draft.original.toByteArray())
            if (!matches) {
                command("UNWATCH").expectOk()
                MutationResult(MutationStatus.CONFLICT, "键已变化或过期，未写入；请刷新")
            } else {
                command("MULTI").expectOk()
                if (draft.kind == MutationKind.DELETE) command("DEL", draft.identity)
                else if (draft.kind == MutationKind.INSERT) command("SET", draft.identity, draft.replacement, "NX")
                else command("SET", draft.identity, draft.replacement, "XX", "KEEPTTL")
                executing = true
                val result = command("EXEC")
                if (result == Resp.Nil) MutationResult(MutationStatus.CONFLICT, "提交前键被修改，事务未执行；请刷新")
                else {
                    val reply = result.array().single()
                    if (draft.kind == MutationKind.DELETE) check(reply.integer() == 1L) else reply.expectOk()
                    MutationResult(MutationStatus.COMMITTED, "单键变更已提交；修改保留当前过期时间")
                }
            }
        } } catch (_: Exception) {
            close()
            MutationResult(if (executing) MutationStatus.UNKNOWN else MutationStatus.ROLLED_BACK,
                if (executing) "执行结果未知，请重新连接核对；不会自动重试" else "未执行写入；请检查键类型、权限及服务器是否支持事务和 KEEPTTL")
        }
    }
    private fun keyCommand(name: String, key: BinaryKey, vararg args: String) = send(
        listOf(name.toByteArray(Charsets.US_ASCII), key.bytes()) + args.map { it.toByteArray(Charsets.UTF_8) })
    private fun command(vararg args: String) = send(args.map { it.toByteArray(Charsets.UTF_8) })
    private fun send(args: List<ByteArray>): Resp {
        check(!isClosed())
        val out = output ?: throw SocketException("Not connected")
        out.write("*${args.size}\r\n".toByteArray(Charsets.US_ASCII))
        args.forEach { value ->
            require(value.size <= 8192)
            out.write("$${value.size}\r\n".toByteArray(Charsets.US_ASCII)); out.write(value); out.write(byteArrayOf(13, 10))
        }
        out.flush()
        return reader!!.read()
    }
    private fun <T> bounded(seconds: Long = 10, operation: () -> T): T {
        check(!isClosed())
        val expired = AtomicBoolean(false)
        val deadline = timer.schedule({ expired.set(true); close() }, seconds, TimeUnit.SECONDS)
        try {
            val result = operation()
            if (expired.get()) throw SocketTimeoutException("Deadline exceeded")
            return result
        } catch (error: Exception) {
            // Any framing/budget/transport failure discards the stream; it is never reused or retried.
            close()
            if (expired.get()) throw SocketTimeoutException("Deadline exceeded")
            throw error
        } finally { deadline.cancel(false) }
    }
    override fun isClosed() = closed.get()
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { raw.close() } catch (_: IOException) { }
            try { socket.close() } catch (_: IOException) { }
            timer.shutdownNow()
        }
    }
    private fun validCursor(value: String) {
        require(value.matches(Regex("0|[1-9][0-9]{0,19}")) && (value.length < 20 || value <= "18446744073709551615"))
    }
}

internal sealed interface Resp {
    class Bytes(val value: ByteArray) : Resp
    class Number(val value: Long) : Resp
    class Items(val values: List<Resp>) : Resp
    data object Nil : Resp
    fun bytes(): ByteArray = (this as? Bytes)?.value ?: throw IOException("Unexpected RESP type")
    fun text() = String(bytes(), Charsets.UTF_8)
    fun integer() = (this as? Number)?.value ?: throw IOException("Unexpected RESP type")
    fun array() = (this as? Items)?.values ?: throw IOException("Unexpected RESP type")
    fun expectOk() { if (text() != "OK") throw IOException("Unexpected reply") }
}
/** Limits apply before allocation, including nested replies; response texts are never used as errors. */
internal class RespReader(private val input: InputStream) {
    private var budget = 0
    private var nodes = 0
    fun read(): Resp { budget = 262144; nodes = 4096; return value(0) }
    private fun byte(): Int {
        if (--budget < 0) throw IOException("Response budget exceeded")
        return input.read().also { if (it < 0) throw EOFException() }
    }
    private fun line(): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val c = byte()
            if (c == 13) { if (byte() != 10) throw IOException("Invalid CRLF"); break }
            if (c == 10 || bytes.size() >= 1024) throw IOException("Invalid line")
            bytes.write(c)
        }
        return bytes.toString("US-ASCII")
    }
    private fun length() = line().toLongOrNull() ?: throw IOException("Invalid length")
    private fun value(depth: Int): Resp {
        if (depth > 8 || --nodes < 0) throw IOException("Nesting budget exceeded")
        return when (byte().toChar()) {
            '+' -> Resp.Bytes(line().toByteArray(Charsets.US_ASCII))
            ':' -> Resp.Number(length())
            '-' -> {
                val code = line().substringBefore(' ')
                val state = when (code) {
                    "WRONGPASS", "NOAUTH" -> "28000"
                    "NOPERM" -> "42501"
                    "MOVED", "ASK" -> "0A000"
                    else -> "HY000"
                }
                throw SQLException("Key-value server rejected operation", state)
            }
            '$' -> {
                val count = length()
                if (count == -1L) Resp.Nil else {
                    if (count !in 0..8192 || count + 2 > budget) throw IOException("Bulk budget exceeded")
                    val data = ByteArray(count.toInt())
                    for (i in data.indices) data[i] = byte().toByte()
                    if (byte() != 13 || byte() != 10) throw IOException("Invalid CRLF")
                    Resp.Bytes(data)
                }
            }
            '*' -> {
                val count = length()
                if (count == -1L) Resp.Nil else {
                    if (count < 0 || count > nodes) throw IOException("Array budget exceeded")
                    Resp.Items(List(count.toInt()) { value(depth + 1) })
                }
            }
            else -> throw IOException("Unsupported RESP frame")
        }
    }
}
