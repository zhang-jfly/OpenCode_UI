package ai.opencode.ide.jetbrains.util

/**
 * Helpers for parsing the OpenCode CLI version.
 */
object CliVersion {

    /**
     * Extracts the major version from `opencode --version` output.
     *
     * The CLI prints values like `opencode v2.2.5`, so the pattern must not use a
     * leading word boundary: there is none between `v` and `2`.
     */
    fun parseMajor(output: String): Int? {
        val match = VERSION_PATTERN.find(output.trim()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private val VERSION_PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)""")
}
