package com.example.datasail.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.Assert.*
import org.junit.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

class AiHttpsTest {
    companion object {
        private lateinit var ssl: SSLContext
        @BeforeClass @JvmStatic fun certificate() {
            val directory = Files.createTempDirectory("datasail-ai-tls").toFile()
            try {
                val file = java.io.File(directory, "fixture.p12")
                val process = ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", file.path, "-storepass", "synthetic-only", "-keypass", "synthetic-only", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2").redirectErrorStream(true).start()
                process.inputStream.use { it.readBytes() }; check(process.waitFor() == 0)
                val keys = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "synthetic-only".toCharArray()) } }
                val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "synthetic-only".toCharArray()) }
                val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
                ssl = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
            } finally { directory.deleteRecursively() }
        }
    }
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { httpsConfigurator = HttpsConfigurator(ssl); this.executor = this@AiHttpsTest.executor }
    @After fun close() { server.stop(0); executor.shutdownNow() }
    private fun service(protocol: AiProtocol = AiProtocol.OPENAI, path: String = "v1", host: String = "localhost") = AiService(protocol, "https://$host:${server.address.port}/$path")
    private fun response(exchange: com.sun.net.httpserver.HttpExchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray(); exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
    }
    @Test fun actualHttpsRequestsUseProtocolSpecificAuthenticationAndPaths() {
        val requests = CopyOnWriteArrayList<String>()
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            when {
                exchange.requestURI.path.startsWith("/openai") -> assertEquals("Bearer synthetic-key", exchange.requestHeaders.getFirst("Authorization"))
                exchange.requestURI.path.startsWith("/claude") -> { assertEquals("synthetic-key", exchange.requestHeaders.getFirst("x-api-key")); assertEquals("2023-06-01", exchange.requestHeaders.getFirst("anthropic-version")); assertNull(exchange.requestHeaders.getFirst("Authorization")) }
                else -> { assertEquals("synthetic-key", exchange.requestHeaders.getFirst("x-goog-api-key")); assertNull(exchange.requestURI.query?.contains("synthetic-key")?.takeIf { it }) }
            }
            val gemini = exchange.requestURI.path.startsWith("/gemini")
            val claude = exchange.requestURI.path.startsWith("/claude")
            if (exchange.requestMethod == "GET") response(exchange, if (gemini) """{"models":[{"name":"models/fixture","supportedGenerationMethods":["generateContent"]}]}""" else """{"data":[{"id":"fixture"}],"has_more":false}""")
            else {
                val body = exchange.requestBody.bufferedReader().readText(); assertTrue(body.contains("synthetic question")); assertFalse(body.contains("synthetic-key"))
                response(exchange, if (gemini) """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"ok"}]}}]}""" else if (claude) """{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}""" else """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}""")
            }
        }; server.start()
        AiProtocol.entries.zip(listOf("openai", "claude", "gemini")).forEach { (protocol, path) ->
            val key = "synthetic-key".toCharArray()
            AiClient(service(protocol, path), key, ssl).use { client -> assertEquals(1, client.models().names.size); assertEquals("ok", client.ask("fixture", "synthetic question")) }
            assertTrue(key.all { it == '\u0000' })
        }
        assertEquals(6, requests.size)
    }
    @Test fun redirectsNeverForwardAuthenticationAndErrorsAreSanitized() {
        val received = AtomicInteger()
        server.createContext("/v1/models") { exchange -> exchange.responseHeaders.set("Location", service(path = "secret").base); response(exchange, "synthetic private error", 302) }
        server.createContext("/secret") { received.incrementAndGet(); response(it, "{}") }
        server.createContext("/denied/models") { response(it, "synthetic private error and credential", 401) }
        server.start()
        AiClient(service(), "synthetic".toCharArray(), ssl).use { assertTrue(assertThrows(AiFailure::class.java) { it.models() }.safeMessage.contains("重定向")) }
        assertEquals(0, received.get())
        AiClient(service(path = "denied"), "synthetic".toCharArray(), ssl).use { assertFalse(assertThrows(AiFailure::class.java) { it.models() }.safeMessage.contains("synthetic")) }
    }
    @Test fun wrongHostAndUntrustedCertificatesFailBeforeHttpAuthentication() {
        val received = AtomicInteger(); server.createContext("/") { received.incrementAndGet(); response(it, "{}") }; server.start()
        AiClient(service(host = "127.0.0.1"), "synthetic".toCharArray(), ssl).use { assertThrows(AiFailure::class.java) { it.models() } }
        AiClient(service(), "synthetic".toCharArray()).use { assertThrows(AiFailure::class.java) { it.models() } }
        assertEquals(0, received.get())
    }
    @Test fun cancellationClosesBlockedSocketAndOversizedBodiesReject() {
        val arrived = CountDownLatch(1); val release = CountDownLatch(1)
        server.createContext("/v1/models") { exchange -> arrived.countDown(); release.await(10, TimeUnit.SECONDS); runCatching { response(exchange, "{}") } }
        server.createContext("/large/models") { response(it, "x".repeat(1_048_577)) }; server.start()
        val client = AiClient(service(), "synthetic".toCharArray(), ssl)
        val future = executor.submit<Boolean> { try { client.models(); false } catch (_: AiFailure) { true } }
        try { assertTrue(arrived.await(5, TimeUnit.SECONDS)); client.close(); assertTrue(future.get(3, TimeUnit.SECONDS)) } finally { release.countDown(); client.close() }
        AiClient(service(path = "large"), charArrayOf(), ssl).use { assertTrue(assertThrows(AiFailure::class.java) { it.models() }.safeMessage.contains("1 MiB")) }
    }
}
