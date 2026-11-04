package ai.opencode.ide.jetbrains.api.models

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.lang.reflect.Type

/**
 * Converts OpenCode V2 events into the internal event model used by the
 * plugin's turn and diff handling.
 */
class V2EventDeserializer : JsonDeserializer<OpenCodeEvent> {

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): OpenCodeEvent {
        if (!json.isJsonObject) return UnknownEvent("unknown")

        val obj = json.asJsonObject
        val type = obj.string("type") ?: return UnknownEvent("unknown")
        val data = obj.objectOrNull("data")

        return when (type) {
            "session.execution.started" -> {
                val sessionId = data?.string("sessionID")
                if (sessionId == null) UnknownEvent(type) else SessionStatusEvent(
                    properties = SessionStatusProperties(
                        sessionID = sessionId,
                        status = SessionStatusType("busy")
                    )
                )
            }

            "session.execution.succeeded",
            "session.execution.failed",
            "session.execution.interrupted",
            "session.idle" -> {
                val sessionId = data?.string("sessionID")
                if (sessionId == null) UnknownEvent(type) else SessionIdleEvent(
                    properties = SessionIdleProperties(sessionId)
                )
            }

            "session.status" -> {
                val sessionId = data?.string("sessionID")
                val statusObject = data?.objectOrNull("status")
                val statusType = statusObject?.string("type")
                if (sessionId == null || statusType == null) UnknownEvent(type) else SessionStatusEvent(
                    properties = SessionStatusProperties(
                        sessionID = sessionId,
                        status = SessionStatusType(
                            type = statusType,
                            attempt = statusObject.int("attempt"),
                            message = statusObject.string("message"),
                            next = statusObject.long("next")
                        )
                    )
                )
            }

            "filesystem.changed" -> {
                val file = data?.string("file")
                if (file == null) UnknownEvent(type) else FileEditedEvent(
                    properties = FileEditedProperties(file)
                )
            }

            "session.message.content.updated" -> {
                val sessionId = data?.string("sessionID")
                val messageId = data?.string("messageID")
                if (sessionId == null || messageId == null) UnknownEvent(type) else MessageUpdatedEvent(
                    properties = MessageUpdatedProperties(
                        info = MessageInfo(id = messageId, sessionID = sessionId, role = "assistant")
                    )
                )
            }

            else -> UnknownEvent(type)
        }
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? {
        val element = get(name) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun JsonObject.string(name: String): String? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return element.asString.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.int(name: String): Int? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return try {
            element.asInt
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.long(name: String): Long? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return try {
            element.asLong
        } catch (_: Exception) {
            null
        }
    }
}
