package com.example.datasail.core

import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLContext

class TlsTransportIsolationTest {
    @Test fun separateHttpOperationsCannotShareTheJvmHttpConnectionCacheKey() {
        val tls = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        ClosingTlsContext(tls).use { context ->
            val first = context.socketFactory
            val next = context.socketFactory
            assertNotSame(first, next)
            val socket = first.createSocket()
            context.close()
            assertTrue(socket.isClosed)
            assertThrows(java.net.SocketException::class.java) { next.createSocket() }
        }
    }
}
