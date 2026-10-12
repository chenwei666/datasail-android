package com.example.datasail.fixture

import com.example.datasail.core.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Synthetic fixtures only; shared assertions execute unchanged on the JVM and ART. */
class KeyValueContract(private val profile: ConnectionProfile, private val password: String) {
    private fun connect(p: ConnectionProfile = profile, pass: String = password): RespKeyValueSession {
        val session = RespKeyValueSession(p)
        try { session.login(Credentials(pass.toCharArray())); return session }
        catch (error: Exception) { session.close(); throw error }
    }
    fun reads() = connect().use { session ->
        val keys = mutableSetOf<BinaryKey>(); var cursor = "0"; var pages = 0
        do {
            val page = session.scan(cursor); cursor = page.cursor; keys.addAll(page.keys)
            assertTrue(++pages < 100)
        } while (cursor != "0")
        assertEquals(212, keys.size)
        fun inspect(name: String) = session.inspect(BinaryKey(name.toByteArray()))
        val string = inspect("string"); assertEquals("string", string.type); assertEquals(listOf(listOf("0007 数舟")), string.rows)
        assertEquals(-1L, string.ttlMillis)
        assertEquals("list", inspect("list").type); assertEquals(listOf(listOf("a"), listOf("b")), inspect("list").rows)
        assertEquals(listOf(listOf("name", "0007")), inspect("hash").rows)
        assertEquals(setOf("a", "b"), inspect("set").rows.flatten().toSet())
        assertEquals(listOf(listOf("0007", "1.5")), inspect("zset").rows)
        assertEquals(listOf(listOf("1-0", "name", "0007")), inspect("stream").rows)
        assertTrue(inspect("expiring").ttlMillis > 0)
        assertEquals("none", inspect("missing").type)
        val long = inspect("long"); assertTrue(long.limited); assertEquals(4096L, long.length); assertEquals(2048, long.rows.single().single().length)
        val binary = BinaryKey(byteArrayOf(0, -1, 13, 10)); assertTrue(keys.contains(binary)); assertEquals(listOf(listOf("hex:00ff")), session.inspect(binary).rows)
        assertEquals(listOf(listOf("special")), session.inspect(BinaryKey("odd\r\n*\"".toByteArray())).rows)
        assertEquals(emptyList<BinaryKey>(), session.scan("0", "absent:*").keys)
        // Stable fixture with >50 hash fields exercises cursor continuation without dropping entries.
        val values = mutableMapOf<String, String>(); cursor = "0"; pages = 0
        do {
            val part = session.inspect(BinaryKey("largehash".toByteArray()), cursor); cursor = part.cursor
            part.rows.forEach { values[it[0]] = it[1] }; assertTrue(++pages < 100)
        } while (cursor != "0")
        assertEquals(600, values.size)
        // A large list element is rejected before allocation and the stream is disposed.
        assertThrows(Exception::class.java) { inspect("oversized") }; assertTrue(session.isClosed())
    }
    fun identityAndAuth() {
        val wrongHost = ConnectionProfile(profile.kind, "127.0.0.1", profile.port, "0", profile.username, trustStore = profile.trustStore)
        val unknownCa = ConnectionProfile(profile.kind, "localhost", profile.port, "0", profile.username)
        for (p in listOf(wrongHost, unknownCa)) {
            val error = assertThrows(Exception::class.java) { connect(p).close() }
            assertEquals(ErrorKind.TLS, Failures.classify(error).kind)
        }
        val error = assertThrows(Exception::class.java) { connect(pass = "invalid").close() }
        assertEquals(ErrorKind.AUTHENTICATION, Failures.classify(error).kind)
        assertFalse(error.message.orEmpty().contains(password))
    }
    fun deadlinesAndCancellation() {
        connect().use { session ->
            pause(16000)
            val start = System.nanoTime()
            val error = assertThrows(Exception::class.java) { session.scan() }
            assertEquals(ErrorKind.TIMEOUT, Failures.classify(error).kind)
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) in 8..14)
            assertTrue(session.isClosed())
        }
        connect().use { session ->
            pause(5000)
            val done = CountDownLatch(1)
            val worker = Thread { try { session.scan() } catch (_: Exception) {} finally { done.countDown() } }
            worker.start(); Thread.sleep(300); session.close()
            assertTrue(done.await(3, TimeUnit.SECONDS)); worker.join(1000); assertTrue(session.isClosed())
        }
    }
    private fun pause(millis: Int) {
        val trust = profile.trustStore!!
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(SqlServerTrustManager("localhost\n${trust.type}\n${trust.path}")), null) }
        (context.socketFactory.createSocket("localhost", profile.port) as SSLSocket).use { socket ->
            socket.soTimeout = 20000; socket.startHandshake()
            fun command(vararg args: String) {
                val out = socket.outputStream
                out.write("*${args.size}\r\n".toByteArray())
                args.forEach { arg -> val bytes = arg.toByteArray(); out.write("$${bytes.size}\r\n".toByteArray()); out.write(bytes); out.write("\r\n".toByteArray()) }
                out.flush()
                val bytes = ByteArray(5)
                for (i in bytes.indices) bytes[i] = socket.inputStream.read().toByte()
                assertEquals("+OK\r\n", String(bytes))
            }
            command("AUTH", "pauser", password); command("CLIENT", "PAUSE", millis.toString(), "ALL")
        }
    }
}
