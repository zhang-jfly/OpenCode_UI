package ai.opencode.ide.jetbrains.api.models

/**
 * OpenCode V2 server info returned by `GET /api/info`.
 */
data class V2ServerInfo(
    val version: String,
    val pid: Long = 0,
    val hostname: String? = null,
    val urls: List<String>? = null
)

/**
 * Most V2 endpoints wrap their payload in a `data` property.
 */
data class V2DataResponse<T>(
    val data: T? = null
)

data class V2SessionInfo(
    val id: String,
    val parentID: String? = null,
    val projectID: String = "",
    val title: String? = null,
    val time: V2SessionTime? = null
)

data class V2SessionTime(
    val created: Double = 0.0,
    val updated: Double = 0.0
)

data class V2SessionsResponse(
    val data: List<V2SessionInfo>? = null
)

data class V2ActiveSessionsResponse(
    val data: Map<String, V2ActiveSession>? = null
)

data class V2ActiveSession(
    val type: String = "running"
)

/**
 * V2 diff entry. Unlike V1 it contains a unified patch instead of before/after
 * file bodies.
 */
data class V2FileDiffInfo(
    val file: String,
    val patch: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String = "modified",
    /** True when the server truncated `patch`; the diff can still be shown, but `before` may be unavailable. */
    val more: Boolean = false
)

data class V2FileDiffsResponse(
    val data: List<V2FileDiffInfo>? = null
)
