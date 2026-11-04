package ai.opencode.ide.jetbrains.api.models

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that OpenCode V2 events are translated into the internal event model.
 */
class V2EventDeserializerTest {

    private val gson = GsonBuilder()
        .registerTypeAdapter(OpenCodeEvent::class.java, V2EventDeserializer())
        .create()

    private fun parse(json: String): OpenCodeEvent = gson.fromJson(json, OpenCodeEvent::class.java)

    @Test
    fun testExecutionStartedMapsToBusyStatus() {
        val event = parse(
            """
            {"id":"evt_1","created":1,"type":"session.execution.started",
             "data":{"sessionID":"ses_1"}}
            """.trimIndent()
        )

        assertTrue(event is SessionStatusEvent)
        val status = event as SessionStatusEvent
        assertEquals("ses_1", status.properties.sessionID)
        assertTrue(status.properties.status.isBusy())
    }

    @Test
    fun testExecutionSucceededMapsToIdle() {
        val event = parse(
            """{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}"""
        )

        assertTrue(event is SessionIdleEvent)
        assertEquals("ses_1", (event as SessionIdleEvent).properties.sessionID)
    }

    @Test
    fun testExecutionFailedMapsToIdle() {
        val event = parse(
            """{"type":"session.execution.failed","data":{"sessionID":"ses_2"}}"""
        )

        assertTrue(event is SessionIdleEvent)
        assertEquals("ses_2", (event as SessionIdleEvent).properties.sessionID)
    }

    @Test
    fun testSessionIdleMapsToIdle() {
        val event = parse(
            """{"type":"session.idle","data":{"sessionID":"ses_3"}}"""
        )

        assertTrue(event is SessionIdleEvent)
        assertEquals("ses_3", (event as SessionIdleEvent).properties.sessionID)
    }

    @Test
    fun testSessionStatusMapsToStatusEvent() {
        val busy = parse(
            """{"type":"session.status","data":{"sessionID":"ses_4","status":{"type":"busy"}}}"""
        )
        assertTrue(busy is SessionStatusEvent)
        assertTrue((busy as SessionStatusEvent).properties.status.isBusy())

        val idle = parse(
            """{"type":"session.status","data":{"sessionID":"ses_4","status":{"type":"idle"}}}"""
        )
        assertTrue(idle is SessionStatusEvent)
        assertTrue((idle as SessionStatusEvent).properties.status.isIdle())

        val retry = parse(
            """{"type":"session.status","data":{"sessionID":"ses_4",
                "status":{"type":"retry","attempt":2,"message":"rate limited","next":1000}}}"""
        )
        assertTrue(retry is SessionStatusEvent)
        val retryStatus = (retry as SessionStatusEvent).properties.status
        assertTrue(retryStatus.isRetry())
        assertEquals(2, retryStatus.attempt)
        assertEquals("rate limited", retryStatus.message)
        assertEquals(1000L, retryStatus.next)
    }

    @Test
    fun testFilesystemChangedMapsToFileEdited() {
        val event = parse(
            """{"type":"filesystem.changed","data":{"file":"src/App.kt","event":"change"}}"""
        )

        assertTrue(event is FileEditedEvent)
        assertEquals("src/App.kt", (event as FileEditedEvent).properties.file)
    }

    @Test
    fun testMessageContentUpdatedMapsToMessageUpdated() {
        val event = parse(
            """{"type":"session.message.content.updated",
                "data":{"sessionID":"ses_5","messageID":"msg_5","content":[]}}"""
        )

        assertTrue(event is MessageUpdatedEvent)
        val info = (event as MessageUpdatedEvent).properties.info
        assertEquals("ses_5", info.sessionID)
        assertEquals("msg_5", info.id)
        assertEquals("assistant", info.role)
    }

    @Test
    fun testUnknownEventIsIgnored() {
        val event = parse("""{"type":"session.tool.progress","data":{"sessionID":"ses_6"}}""")

        assertTrue(event is UnknownEvent)
        assertEquals("session.tool.progress", event.type)
    }

    @Test
    fun testMissingTypeFieldIsUnknown() {
        val event = parse("""{"data":{"sessionID":"ses_7"}}""")

        assertTrue(event is UnknownEvent)
    }

    @Test
    fun testInvalidJsonPayloadIsRejectedSafely() {
        val element = JsonParser.parseString("[]")
        val event = gson.fromJson(element, OpenCodeEvent::class.java)
        assertTrue(event is UnknownEvent)
    }
}
