package ai.opencode.ide.jetbrains.api.v2

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * Tests the V2 REST client against a fake server: `{data: ...}` unwrapping and
 * patch-based diff reconstruction.
 */
class OpenCodeV2ApiClientTest {

    private var server: HttpServer? = null
    private var projectDir: File? = null
    private var port: Int = 0

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
        projectDir?.deleteRecursively()
        projectDir = null
    }

    @Test
    fun testGetSessionsUnwrapsDataEnvelope() {
        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/session" -> respondJson(
                    exchange,
                    """{"data":[
                        {"id":"ses_1","projectID":"prj_1","title":"First","time":{"created":1.0,"updated":2.0}},
                        {"id":"ses_2","projectID":"prj_1","title":"Second","parentID":"ses_1","time":{"created":3.0,"updated":4.0}}
                    ]}"""
                )
                else -> respondJson(exchange, """{"data":[]}""")
            }
        }

        val client = newClient()
        val sessions = client.getSessions(projectDirPath())

        assertEquals(2, sessions.size)
        assertEquals("ses_1", sessions[0].id)
        assertEquals("First", sessions[0].title)
        assertEquals(2.0, sessions[0].time?.updated ?: 0.0, 0.001)
        assertEquals("ses_1", sessions[1].parentID)
    }

    @Test
    fun testGetSessionStatusesMarksRunningSessionsBusy() {
        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/session/active" -> respondJson(
                    exchange,
                    """{"data":{"ses_1":{"type":"running"},"ses_2":{"type":"idle"}}}"""
                )
                else -> respondJson(exchange, """{"data":[]}""")
            }
        }

        val statuses = newClient().getSessionStatuses(projectDirPath()).associateBy { it.sessionID }

        assertTrue(statuses.getValue("ses_1").status.isBusy())
        assertTrue(statuses.getValue("ses_2").status.isIdle())
    }

    @Test
    fun testGetSessionUnwrapsSingleObject() {
        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/session/ses_9" -> respondJson(
                    exchange,
                    """{"data":{"id":"ses_9","projectID":"prj_1","title":"Single","time":{"created":1.0,"updated":5.0}}}"""
                )
                else -> respondJson(exchange, """{"data":null}""")
            }
        }

        val session = newClient().getSession("ses_9", projectDirPath())

        assertNotNull(session)
        assertEquals("ses_9", session?.id)
        assertEquals("Single", session?.title)
    }

    @Test
    fun testGetSessionDiffReconstructsBeforeFromPatch() {
        val dir = createProject(
            "src/App.kt" to "fun main() {\n    println(\"new\")\n    println(\"extra\")\n    return\n}\n"
        )

        val patch = """
            --- a/src/App.kt
            +++ b/src/App.kt
            @@ -1,4 +1,5 @@
             fun main() {
            -    println("old")
            +    println("new")
            +    println("extra")
                 return
             }
        """.trimIndent() + "\n"

        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/session/ses_1/diff" -> respondJson(
                    exchange,
                    """{"data":[{"file":"src/App.kt","patch":${jsonString(patch)},"additions":2,"deletions":1,"status":"modified"}]}"""
                )
                else -> respondJson(exchange, """{"data":[]}""")
            }
        }

        val diffs = newClient().getSessionDiff("ses_1", dir.absolutePath)

        assertEquals(1, diffs.size)
        val diff = diffs[0]
        assertEquals("src/App.kt", diff.file)
        assertEquals("modified", diff.status)
        assertEquals(2, diff.additions)
        assertEquals(1, diff.deletions)
        assertEquals(File(dir, "src/App.kt").readText(), diff.after)
        assertEquals("fun main() {\n    println(\"old\")\n    return\n}\n", diff.before)
    }

    @Test
    fun testGetSessionDiffHandlesDeletedFiles() {
        val dir = createProject()

        val patch = """
            --- a/gone.txt
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -first
            -second
        """.trimIndent() + "\n"

        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/session/ses_2/diff" -> respondJson(
                    exchange,
                    """{"data":[{"file":"gone.txt","patch":${jsonString(patch)},"additions":0,"deletions":2,"status":"deleted"}]}"""
                )
                else -> respondJson(exchange, """{"data":[]}""")
            }
        }

        val diffs = newClient().getSessionDiff("ses_2", dir.absolutePath)

        assertEquals(1, diffs.size)
        assertEquals("deleted", diffs[0].status)
        assertEquals("", diffs[0].after)
        assertEquals("first\nsecond\n", diffs[0].before)
    }

    @Test
    fun testGetSessionDiffHandlesAddedFiles() {
        val dir = createProject("added.md" to "hello\nworld\n")

        val patch = """
            --- /dev/null
            +++ b/added.md
            @@ -0,0 +1,2 @@
            +hello
            +world
        """.trimIndent() + "\n"

        startServer { exchange ->
            when (exchange.requestURI.path) {
                "/api/session/ses_3/diff" -> respondJson(
                    exchange,
                    """{"data":[{"file":"added.md","patch":${jsonString(patch)},"additions":2,"deletions":0,"status":"added"}]}"""
                )
                else -> respondJson(exchange, """{"data":[]}""")
            }
        }

        val diffs = newClient().getSessionDiff("ses_3", dir.absolutePath)

        assertEquals(1, diffs.size)
        assertEquals("added", diffs[0].status)
        assertEquals("", diffs[0].before)
        assertEquals("hello\nworld\n", diffs[0].after)
    }

    @Test
    fun testTuiAppendPromptIsUnsupported() {
        startServer { exchange -> respondJson(exchange, """{"data":null}""") }

        assertEquals(false, newClient().tuiAppendPrompt("hello"))
    }

    private fun newClient(): OpenCodeV2ApiClient =
        OpenCodeV2ApiClient("127.0.0.1", port, "opencode", null)

    private fun projectDirPath(): String {
        return projectDir?.absolutePath ?: createProject().absolutePath
    }

    private fun createProject(vararg files: Pair<String, String>): File {
        val dir = Files.createTempDirectory("opencode-v2-test").toFile()
        projectDir = dir
        files.forEach { (path, content) ->
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return dir
    }

    private fun startServer(handler: (HttpExchange) -> Unit) {
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
        port = httpServer.address.port
    }

    private fun respondJson(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun jsonString(value: String): String = gsonEscape(value)

    /** Minimal JSON string escaping for embedding patches in the fake payload. */
    private fun gsonEscape(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}
