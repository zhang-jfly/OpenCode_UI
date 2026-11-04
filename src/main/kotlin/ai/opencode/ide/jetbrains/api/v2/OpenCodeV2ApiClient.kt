package ai.opencode.ide.jetbrains.api.v2

import ai.opencode.ide.jetbrains.api.OpenCodeApi
import ai.opencode.ide.jetbrains.api.OpenCodeProtocol
import ai.opencode.ide.jetbrains.api.ProtocolDetector
import ai.opencode.ide.jetbrains.api.SseEventListener
import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.Session
import ai.opencode.ide.jetbrains.api.models.SessionStatus
import ai.opencode.ide.jetbrains.api.models.SessionStatusType
import ai.opencode.ide.jetbrains.api.models.SessionTime
import ai.opencode.ide.jetbrains.api.models.V2ActiveSessionsResponse
import ai.opencode.ide.jetbrains.api.models.V2DataResponse
import ai.opencode.ide.jetbrains.api.models.V2EventDeserializer
import ai.opencode.ide.jetbrains.api.models.V2FileDiffInfo
import ai.opencode.ide.jetbrains.api.models.V2FileDiffsResponse
import ai.opencode.ide.jetbrains.api.models.V2SessionInfo
import ai.opencode.ide.jetbrains.api.models.V2SessionsResponse
import ai.opencode.ide.jetbrains.util.PatchUtil
import ai.opencode.ide.jetbrains.util.PathUtil
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the OpenCode V2 API (all endpoints under `/api`).
 */
class OpenCodeV2ApiClient(
    private val hostname: String,
    private val port: Int,
    private val username: String = "opencode",
    private val password: String? = null
) : OpenCodeApi {

    override val protocol: OpenCodeProtocol = OpenCodeProtocol.V2

    private val logger = Logger.getInstance(OpenCodeV2ApiClient::class.java)
    private val baseUrl = "http://$hostname:$port"

    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (password != null) {
                addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(username, password))
                        .build()
                    chain.proceed(request)
                }
            }
        }
        .build()

    private val gson: Gson = GsonBuilder().create()

    override fun getSessions(directory: String): List<Session> {
        val serverPath = PathUtil.toOpenCodeServerPath(directory)
        val url = "$baseUrl/api/session?directory=${encode(serverPath)}"
        val type = object : TypeToken<V2SessionsResponse>() {}.type
        val response: V2SessionsResponse? = get(url, type)
        return response?.data.orEmpty().map { it.toSession(directory) }
    }

    override fun getSessionStatuses(directory: String): List<SessionStatus> {
        val url = "$baseUrl/api/session/active"
        val type = object : TypeToken<V2ActiveSessionsResponse>() {}.type
        val response: V2ActiveSessionsResponse? = get(url, type)
        return response?.data.orEmpty().map { (sessionId, active) ->
            val status = if (active.type == "running") "busy" else active.type
            SessionStatus(sessionId, SessionStatusType(status))
        }
    }

    override fun getSessionDiff(sessionId: String, directory: String, messageId: String?): List<FileDiff> {
        // V2 resolves the newest user turn by default. The recorded message ID is
        // an assistant message and is not a valid `from` value, so it is ignored.
        val url = "$baseUrl/api/session/$sessionId/diff"
        val type = object : TypeToken<V2FileDiffsResponse>() {}.type
        val response: V2FileDiffsResponse? = get(url, type)
        val diffs = response?.data.orEmpty().map { it.toFileDiff(directory) }

        if (diffs.isNotEmpty()) {
            logger.info("[V2 API] Received ${diffs.size} diffs for session $sessionId")
            diffs.forEach {
                logger.debug("[V2 API] Diff: ${it.file} status=${it.status} +${it.additions}/-${it.deletions}")
            }
        }
        return diffs
    }

    override fun getSession(sessionId: String, directory: String): Session? {
        val url = "$baseUrl/api/session/$sessionId"
        val type = object : TypeToken<V2DataResponse<V2SessionInfo>>() {}.type
        val response: V2DataResponse<V2SessionInfo>? = get(url, type)
        return response?.data?.toSession(directory)
    }

    override fun checkHealth(directory: String): Boolean {
        return ProtocolDetector.probeV2Info(hostname, port, username, password) != null
    }

    override fun createEventListener(
        directory: String,
        onEvent: (OpenCodeEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit
    ): SseEventListener {
        return SseEventListener(
            baseUrl = baseUrl,
            projectPath = PathUtil.toOpenCodeServerPath(directory),
            onEvent = onEvent,
            onError = onError,
            onConnected = onConnected,
            onDisconnected = onDisconnected,
            username = username,
            password = password,
            eventPath = "/api/event",
            appendDirectory = false,
            deserializer = V2EventDeserializer()
        )
    }

    override fun tuiAppendPrompt(text: String): Boolean {
        // V2 no longer exposes the TUI append-prompt endpoint.
        logger.debug("[V2 API] tuiAppendPrompt is not supported; falling back to terminal input")
        return false
    }

    private fun V2SessionInfo.toSession(directory: String): Session {
        return Session(
            id = id,
            projectID = projectID,
            directory = directory,
            title = title ?: "",
            parentID = parentID,
            summary = null,
            time = SessionTime(updated = time?.updated ?: 0.0)
        )
    }

    private fun V2FileDiffInfo.toFileDiff(directory: String): FileDiff {
        val relativePath = normalizeRelativePath(directory, file)
        val afterContent = if (status == "deleted") "" else readFileContent(directory, relativePath)
        val beforeContent = when {
            status == "added" -> ""
            patch.isNotBlank() -> PatchUtil.reverseApply(afterContent, patch) ?: ""
            else -> ""
        }
        if (more) {
            logger.warn(
                "[V2 API] Patch for $relativePath was truncated by the server; " +
                    "the original content will be resolved from IDE history instead"
            )
        }

        return FileDiff(
            file = relativePath,
            before = beforeContent,
            after = afterContent,
            additions = additions,
            deletions = deletions,
            patch = patch,
            status = status
        )
    }

    private fun normalizeRelativePath(directory: String, rawPath: String): String {
        val normalized = PathUtil.toSystemIndependentPath(rawPath.trim())
        val looksAbsolute = normalized.startsWith("/") ||
            Regex("^[A-Za-z]:/").containsMatchIn(normalized)
        val relative = if (looksAbsolute) {
            PathUtil.relativizeToProject(directory, normalized)
        } else {
            normalized
        }
        return relative.removePrefix("./")
    }

    private fun readFileContent(directory: String, relativePath: String): String {
        val absolutePath = PathUtil.resolveToSystemIndependentPath(directory, relativePath) ?: return ""
        return try {
            val file = File(absolutePath)
            if (file.isFile) file.readText() else ""
        } catch (e: Exception) {
            logger.debug("[V2 API] Failed to read $relativePath: ${e.message}")
            ""
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun <T> get(url: String, type: java.lang.reflect.Type): T? {
        val request = Request.Builder().url(url).get().build()
        return execute(request, type)
    }

    private fun <T> execute(request: Request, type: java.lang.reflect.Type): T? {
        val startTime = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    val bodyBytes = response.body?.bytes()
                    val jsonString = bodyBytes?.toString(Charsets.UTF_8)
                    logger.debug("[V2 API] Success: ${request.method} ${request.url} (took ${duration}ms)")
                    gson.fromJson(jsonString, type)
                } else {
                    val bodyPreview = try {
                        response.peekBody(1024).string()
                    } catch (_: Exception) {
                        "no body"
                    }
                    logger.warn(
                        "[V2 API] Failed: ${request.method} ${request.url} " +
                            "(HTTP ${response.code}, took ${duration}ms). Response: $bodyPreview"
                    )
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug("[V2 API] Error: ${request.method} ${request.url} -> ${e.message}")
            null
        }
    }
}
