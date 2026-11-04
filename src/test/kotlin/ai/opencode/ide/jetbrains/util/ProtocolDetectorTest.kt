package ai.opencode.ide.jetbrains.util

import ai.opencode.ide.jetbrains.api.OpenCodeProtocol
import ai.opencode.ide.jetbrains.api.ProtocolDetector
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64

/**
 * Verifies protocol detection. OpenCode V2 serves its web UI (HTML) from legacy
 * V1 paths, so an HTTP 200 must never be treated as a V1 server on its own.
 */
class ProtocolDetectorTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun testDetectsV2FromInfoEndpoint() {
        val port = startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/info" -> respondJson(exchange, 200, """{"version":"2.2.5"}""")
                else -> respondHtml(exchange)
            }
        }

        assertEquals(OpenCodeProtocol.V2, ProtocolDetector.detect("127.0.0.1", port))
        assertNotNull(ProtocolDetector.probeV2Info("127.0.0.1", port))
    }

    @Test
    fun testHtmlHealthResponseIsNotV1() {
        val port = startServer { exchange ->
            when (exchange.requestURI.path) {
                "/global/health" -> respondHtml(exchange)
                else -> respondHtml(exchange)
            }
        }

        assertEquals(OpenCodeProtocol.UNKNOWN, ProtocolDetector.detect("127.0.0.1", port))
    }

    @Test
    fun testDetectsV1FromPlainTextHealth() {
        val port = startServer { exchange ->
            when (exchange.requestURI.path) {
                "/global/health" -> respondText(exchange, 200, "OK")
                else -> respondText(exchange, 404, "not found")
            }
        }

        assertEquals(OpenCodeProtocol.V1, ProtocolDetector.detect("127.0.0.1", port))
        assertTrue(ProtocolDetector.isV1Healthy("127.0.0.1", port))
    }

    @Test
    fun testDetectsV1FromJsonHealth() {
        val port = startServer { exchange ->
            when (exchange.requestURI.path) {
                "/global/health" -> respondJson(exchange, 200, """{"healthy":true,"version":"1.0.4"}""")
                else -> respondText(exchange, 404, "not found")
            }
        }

        assertEquals(OpenCodeProtocol.V1, ProtocolDetector.detect("127.0.0.1", port))
    }

    @Test
    fun testUnauthorizedInfoMeansV2() {
        val port = startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/info" -> respondText(exchange, 401, "unauthorized")
                else -> respondHtml(exchange)
            }
        }

        assertEquals(OpenCodeProtocol.V2, ProtocolDetector.detect("127.0.0.1", port))
    }

    @Test
    fun testUnauthorizedHealthMeansV1() {
        val port = startServer { exchange ->
            when (exchange.requestURI.path) {
                "/global/health" -> respondText(exchange, 401, "unauthorized")
                else -> respondText(exchange, 404, "not found")
            }
        }

        assertEquals(OpenCodeProtocol.V1, ProtocolDetector.detect("127.0.0.1", port))
    }

    @Test
    fun testAuthenticatedV2Detection() {
        val password = "secret-password"
        val expected = "Basic " + Base64.getEncoder()
            .encodeToString("opencode:$password".toByteArray(Charsets.UTF_8))

        val port = startServer { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != expected) {
                respondText(exchange, 401, "unauthorized")
            } else {
                respondJson(exchange, 200, """{"version":"2.2.5"}""")
            }
        }

        assertEquals(OpenCodeProtocol.V2, ProtocolDetector.detect("127.0.0.1", port, null, password))
        assertEquals("2.2.5", ProtocolDetector.probeV2Info("127.0.0.1", port, "opencode", password))
        assertNull(ProtocolDetector.probeV2Info("127.0.0.1", port, "opencode", "wrong"))
    }

    private fun startServer(handler: (HttpExchange) -> Unit): Int {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        httpServer.start()
        server = httpServer
        return httpServer.address.port
    }

    private fun respondJson(exchange: HttpExchange, code: Int, body: String) {
        respondText(exchange, code, body, "application/json")
    }

    private fun respondHtml(exchange: HttpExchange) {
        respondText(exchange, 200, "<!doctype html><html><body>OpenCode</body></html>", "text/html")
    }

    private fun respondText(exchange: HttpExchange, code: Int, body: String, contentType: String = "text/plain") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
