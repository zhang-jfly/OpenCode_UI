package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.Session
import ai.opencode.ide.jetbrains.api.models.SessionStatus

/**
 * Server protocol used by an OpenCode instance.
 */
enum class OpenCodeProtocol {
    UNKNOWN,
    V1,
    V2
}

/**
 * Common API surface for OpenCode 1.x and 2.x servers.
 */
interface OpenCodeApi {

    val protocol: OpenCodeProtocol

    fun getSessions(directory: String): List<Session>

    fun getSessionStatuses(directory: String): List<SessionStatus>

    fun findBusySession(directory: String): SessionStatus? =
        getSessionStatuses(directory).firstOrNull { it.status.isBusy() }

    fun getSessionDiff(sessionId: String, directory: String, messageId: String? = null): List<FileDiff>

    fun getSession(sessionId: String, directory: String): Session?

    fun checkHealth(directory: String): Boolean

    fun createEventListener(
        directory: String,
        onEvent: (OpenCodeEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {}
    ): SseEventListener

    /**
     * Append text to the TUI input. V2 servers do not expose this endpoint.
     */
    fun tuiAppendPrompt(text: String): Boolean
}
