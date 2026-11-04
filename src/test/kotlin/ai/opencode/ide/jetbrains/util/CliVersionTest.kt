package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for parsing the OpenCode CLI version output.
 */
class CliVersionTest {

    @Test
    fun testParseV2Version() {
        // The `v` prefix must not prevent matching: there is no word boundary between `v` and `2`.
        assertEquals(2, CliVersion.parseMajor("opencode v2.2.5"))
    }

    @Test
    fun testParseV1Version() {
        assertEquals(1, CliVersion.parseMajor("1.0.4"))
    }

    @Test
    fun testParseVersionWithSurroundingOutput() {
        assertEquals(2, CliVersion.parseMajor("some warning line\nopencode v2.10.0\n"))
    }

    @Test
    fun testParseInvalidOutput() {
        assertNull(CliVersion.parseMajor(""))
        assertNull(CliVersion.parseMajor("opencode"))
        assertNull(CliVersion.parseMajor("version unknown"))
    }
}
