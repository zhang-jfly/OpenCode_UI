package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for building OpenCode CLI server URLs with embedded credentials.
 */
class CliCommandTest {

    @Test
    fun testServerUrlWithoutPassword() {
        assertEquals("http://127.0.0.1:4096", CliCommand.buildServerUrl("127.0.0.1", 4096))
        assertEquals("http://127.0.0.1:4096", CliCommand.buildServerUrl("127.0.0.1", 4096, password = ""))
    }

    @Test
    fun testServerUrlWithSimplePassword() {
        assertEquals(
            "http://opencode:secret@127.0.0.1:4096",
            CliCommand.buildServerUrl("127.0.0.1", 4096, password = "secret")
        )
    }

    @Test
    fun testServerUrlEncodesReservedCharacters() {
        // Reserved characters must be percent-encoded so the shell cannot split
        // the argument (e.g. `&`) and URL parsing decodes the original password.
        assertEquals(
            "http://opencode:p%40ss%3Aw%2Frd%25%23%3F%26x@127.0.0.1:4197",
            CliCommand.buildServerUrl("127.0.0.1", 4197, password = "p@ss:w/rd%#?&x")
        )
    }

    @Test
    fun testServerUrlEncodesSpacesAsPercent20() {
        assertEquals(
            "http://opencode:a%20b@localhost:4096",
            CliCommand.buildServerUrl("localhost", 4096, password = "a b")
        )
    }

    @Test
    fun testServerUrlEncodesNonAsciiPassword() {
        assertEquals(
            "http://opencode:p%C3%A4ss@localhost:4096",
            CliCommand.buildServerUrl("localhost", 4096, password = "päss")
        )
    }

    @Test
    fun testServerUrlEncodesUsername() {
        assertEquals(
            "http://open%20code:pw@localhost:4096",
            CliCommand.buildServerUrl("localhost", 4096, username = "open code", password = "pw")
        )
    }

    @Test
    fun testServerUrlBracketsIpv6Host() {
        assertEquals(
            "http://opencode:pw@[::1]:4096",
            CliCommand.buildServerUrl("::1", 4096, password = "pw")
        )
    }

    @Test
    fun testEncodeUserInfoKeepsUnreservedCharacters() {
        assertEquals("aZ0-._~", CliCommand.encodeUserInfo("aZ0-._~"))
    }
}
