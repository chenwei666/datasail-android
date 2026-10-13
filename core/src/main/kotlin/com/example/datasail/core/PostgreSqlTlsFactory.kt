package com.example.datasail.core

import java.net.InetAddress
import java.net.Socket
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/** pgjdbc's documented Properties constructor; shared CA/SAN or explicit leaf-pin verification. */
class PostgreSqlTlsFactory(properties: Properties) : SSLSocketFactory() {
    private val delegate = SSLContext.getInstance("TLS").apply {
        val host = properties.getProperty("datasailTlsHost") ?: error("Missing TLS identity")
        val type = properties.getProperty("trustStoreType", "PKCS12")
        val path = properties.getProperty("trustStore", "")
        require(listOf(host, type, path).none { '\n' in it })
        init(null, arrayOf(SqlServerTrustManager(properties.getProperty("datasailTlsConfiguration", "$host\n$type\n$path"))), null)
    }.socketFactory
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = delegate.createSocket()
    override fun createSocket(s: Socket, host: String, port: Int, close: Boolean): Socket = delegate.createSocket(s, host, port, close)
    override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port)
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = delegate.createSocket(host, port, local, localPort)
    override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = delegate.createSocket(host, port, local, localPort)
}
