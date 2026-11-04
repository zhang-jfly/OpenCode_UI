package ai.opencode.ide.jetbrains.api

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Base64

/**
 * Detects whether an OpenCode server speaks the V1 or V2 protocol.
 *
 * OpenCode V2 serves its web UI from legacy V1 paths, so an HTTP 200 response is
 * not enough to identify a server. The response content type and JSON shape must
 * be validated.
 */
object ProtocolDetector {

    private val logger = Logger.getInstance(ProtocolDetector::class.java)

    private const val CONNECT_TIMEOUT_MS = 2000
    private const val READ_TIMEOUT_MS = 3000

    private data class ProbeResponse(
        val code: Int,
        val body: String,
        val contentType: String?
    )

    fun detect(
        hostname: String,
        port: Int,
        username: String? = null,
        password: String? = null
    ): OpenCodeProtocol {
        val host = normalizeHost(hostname)
        val user = effectiveUsername(username, password)

        val info = request(host, port, "/api/info", user, password)
        if (info != null) {
            if (info.code == 200 && parseVersion(info.body) != null) {
                logger.info("[Protocol] Detected V2 on $host:$port")
                return OpenCodeProtocol.V2
            }
            if (info.code == 401 || info.code == 403) {
                // The V2 info endpoint exists but credentials are missing or wrong.
                logger.info("[Protocol] Detected V2 on $host:$port (authentication required)")
                return OpenCodeProtocol.V2
            }
        }

        val health = request(host, port, "/global/health", user, password)
        if (health != null) {
            if (health.code == 200 && isV1HealthBody(health)) {
                logger.info("[Protocol] Detected V1 on $host:$port")
                return OpenCodeProtocol.V1
            }
            if (health.code == 401 || health.code == 403) {
                logger.info("[Protocol] Detected V1 on $host:$port (authentication required)")
                return OpenCodeProtocol.V1
            }
        }

        logger.info("[Protocol] Unknown server on $host:$port")
        return OpenCodeProtocol.UNKNOWN
    }

    /**
     * Returns the V2 server version, or null when the server is not a ready V2
     * instance.
     */
    fun probeV2Info(
        hostname: String,
        port: Int,
        username: String? = null,
        password: String? = null
    ): String? {
        val host = normalizeHost(hostname)
        val user = effectiveUsername(username, password)
        val response = request(host, port, "/api/info", user, password) ?: return null
        if (response.code != 200) return null
        return parseVersion(response.body)
    }

    fun isV1Healthy(
        hostname: String,
        port: Int,
        username: String? = null,
        password: String? = null
    ): Boolean {
        val host = normalizeHost(hostname)
        val user = effectiveUsername(username, password)
        val response = request(host, port, "/global/health", user, password) ?: return false
        return response.code == 200 && isV1HealthBody(response)
    }

    fun waitForV2Info(
        hostname: String,
        port: Int,
        username: String?,
        password: String?,
        timeoutMs: Long
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (probeV2Info(hostname, port, username, password) != null) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun effectiveUsername(username: String?, password: String?): String? {
        if (username != null) return username
        return if (password != null) "opencode" else null
    }

    private fun parseVersion(body: String): String? {
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("version")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isV1HealthBody(response: ProbeResponse): Boolean {
        val contentType = response.contentType?.lowercase().orEmpty()
        if (contentType.contains("text/html")) return false

        val body = response.body.trim()
        if (body.equals("OK", ignoreCase = true)) return true

        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("healthy")?.takeIf { !it.isJsonNull }?.asBoolean == true
        } catch (_: Exception) {
            false
        }
    }

    private fun request(
        host: String,
        port: Int,
        path: String,
        username: String?,
        password: String?
    ): ProbeResponse? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("http://$host:$port$path")
            connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")

            if (username != null && password != null) {
                val credentials = "$username:$password"
                val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
                connection.setRequestProperty("Authorization", "Basic $encoded")
            }

            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }
            ProbeResponse(code, body, connection.contentType)
        } catch (e: Exception) {
            logger.debug("[Protocol] Probe $path on $host:$port failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun normalizeHost(hostname: String): String {
        val host = hostname.trim().ifBlank { "127.0.0.1" }
        return if (host == "0.0.0.0" || host == "::") "127.0.0.1" else host
    }
}
