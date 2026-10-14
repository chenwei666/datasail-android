package com.example.datasail.core

import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.security.Provider
import java.security.SecureRandom
import javax.net.ssl.*

/** Close checked-out driver sockets as well as idle ones on cancellation; never weaken TLS. */
internal class ClosingTlsContext(delegate: SSLContext, private val registry: SocketRegistry = SocketRegistry()) :
    SSLContext(ClosingTlsSpi(delegate, registry), object : Provider("DataSailClose", 1.0, "Cancellable TLS") {}, "TLS"), AutoCloseable {
    override fun close() = registry.close()
}
internal class SocketRegistry {
    private var closed = false
    private val sockets = mutableSetOf<Socket>()
    @Synchronized fun add(socket: Socket): Socket {
        if (closed) { socket.close(); throw SocketException("Cancelled") }
        sockets.removeAll { it.isClosed }; sockets += socket; return socket
    }
    @Synchronized fun close() { closed = true; sockets.forEach { try { it.close() } catch (_: Exception) {} }; sockets.clear() }
}
private class ClosingTlsSpi(private val delegate: SSLContext, private val registry: SocketRegistry) : SSLContextSpi() {
    private fun freshFactory() = object : SSLSocketFactory() {
        private val original = delegate.socketFactory
        override fun getDefaultCipherSuites() = original.defaultCipherSuites
        override fun getSupportedCipherSuites() = original.supportedCipherSuites
        override fun createSocket(): Socket = registry.add(original.createSocket())
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket = registry.add(original.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String, port: Int): Socket = registry.add(original.createSocket(host, port))
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = registry.add(original.createSocket(host, port, local, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket = registry.add(original.createSocket(host, port))
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = registry.add(original.createSocket(host, port, local, localPort))
    }
    override fun engineInit(km: Array<out KeyManager>?, tm: Array<out TrustManager>?, random: SecureRandom?) = throw UnsupportedOperationException()
    // HttpsURLConnection keys its connection cache by socket-factory identity.
    // A fresh facade prevents reuse of a peer-closed socket across separate HTTP operations.
    override fun engineGetSocketFactory() = freshFactory()
    override fun engineGetServerSocketFactory() = delegate.serverSocketFactory
    override fun engineCreateSSLEngine() = delegate.createSSLEngine()
    override fun engineCreateSSLEngine(host: String, port: Int) = delegate.createSSLEngine(host, port)
    override fun engineGetServerSessionContext() = delegate.serverSessionContext
    override fun engineGetClientSessionContext() = delegate.clientSessionContext
}
