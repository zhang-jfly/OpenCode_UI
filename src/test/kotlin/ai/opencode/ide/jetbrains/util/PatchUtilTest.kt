package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for reverse-applying OpenCode V2 unified patches.
 */
class PatchUtilTest {

    @Test
    fun testReverseApplyModifiedFile() {
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
        """.trimIndent()

        val after = """
            fun main() {
                println("new")
                println("extra")
                return
            }
        """.trimIndent() + "\n"

        assertEquals(
            """
            fun main() {
                println("old")
                return
            }
            """.trimIndent() + "\n",
            PatchUtil.reverseApply(after, patch)
        )
    }

    @Test
    fun testReverseApplyAddedFile() {
        val patch = """
            --- /dev/null
            +++ b/notes/new.md
            @@ -0,0 +1,2 @@
            +hello
            +world
        """.trimIndent()

        assertEquals("", PatchUtil.reverseApply("hello\nworld\n", patch))
    }

    @Test
    fun testReverseApplyDeletedFile() {
        val patch = """
            --- a/obsolete.txt
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -first
            -second
        """.trimIndent()

        assertEquals("first\nsecond\n", PatchUtil.reverseApply("", patch))
    }

    @Test
    fun testReverseApplyMultipleHunks() {
        val patch = """
            --- a/file.txt
            +++ b/file.txt
            @@ -1,2 +1,2 @@
             alpha
            -beta
            +BETA
            @@ -10,2 +10,2 @@
             ten
            -eleven
            +ELEVEN
        """.trimIndent()

        val after = "alpha\nBETA\n3\n4\n5\n6\n7\n8\n9\nten\nELEVEN\n"

        assertEquals(
            "alpha\nbeta\n3\n4\n5\n6\n7\n8\n9\nten\neleven\n",
            PatchUtil.reverseApply(after, patch)
        )
    }

    @Test
    fun testReverseApplyFailsWhenContentDiffers() {
        val patch = """
            --- a/file.txt
            +++ b/file.txt
            @@ -1,1 +1,1 @@
            -old
            +new
        """.trimIndent()

        assertNull(PatchUtil.reverseApply("something else\n", patch))
    }

    @Test
    fun testReverseApplyRejectsBlankPatch() {
        assertNull(PatchUtil.reverseApply("content", ""))
        assertNull(PatchUtil.reverseApply("content", "not a patch"))
    }
}
