package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RespReaderTest {
    private fun read(value: String) = RespReader(value.byteInputStream()).read()
    @Test fun rejectsUnboundedAndMalformedFramesBeforeAllocation() {
        listOf("$9223372036854775807\r\n", "*2147483647\r\n", "$-2\r\n", ":9223372036854775808\r\n", "+bad\n", "$1\r\naXX", "+" + "a".repeat(1025), "*1\r\n".repeat(10) + "+OK\r\n").forEach {
            assertThrows(IOException::class.java) { read(it) }
        }
        assertThrows(IOException::class.java) { read("*40\r\n" + ("$8192\r\n" + "a".repeat(8192) + "\r\n").repeat(40)) }
    }
    @Test fun binaryIdentityAndProtocolErrorsStaySafe() {
        assertArrayEquals(byteArrayOf(0, 13, 10), (read("$3\r\n\u0000\r\n\r\n") as Resp.Bytes).value)
        val key = BinaryKey(byteArrayOf(0, -1)); val changed = key.bytes(); changed[0] = 1
        assertEquals("hex:00ff", key.display)
        for ((code, kind) in listOf("WRONGPASS" to ErrorKind.AUTHENTICATION, "NOPERM" to ErrorKind.PERMISSION, "MOVED" to ErrorKind.UNSUPPORTED)) {
            val error = assertThrows(java.sql.SQLException::class.java) { read("-$code secret-target\r\n") }
            assertEquals(kind, Failures.classify(error).kind); assertFalse(error.message!!.contains("secret"))
        }
    }
    @Test fun cancellationDuringHandshakeDisposesConnectionAndSuppressesLateSuccess() {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1); val closed = CountDownLatch(1)
            val peer = Thread { server.accept().use { socket ->
                socket.soTimeout = 5000; accepted.countDown()
                try { while (socket.inputStream.read() >= 0) { } } catch (_: Exception) { } finally { closed.countDown() }
            } }
            peer.start()
            KeyValueRunner().use { runner ->
                val callback = CountDownLatch(1)
                assertTrue(runner.connect(ConnectionProfile(DatabaseKind.REDIS, "localhost", server.localPort, "0", "default"), Credentials("synthetic".toCharArray())) { callback.countDown() })
                assertTrue(accepted.await(5, TimeUnit.SECONDS)); runner.disconnect()
                assertTrue(closed.await(5, TimeUnit.SECONDS)); assertFalse(callback.await(300, TimeUnit.MILLISECONDS))
            }
            peer.join(5000)
        }
    }
}
