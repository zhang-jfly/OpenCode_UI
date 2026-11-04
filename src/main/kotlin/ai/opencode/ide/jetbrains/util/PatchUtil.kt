package ai.opencode.ide.jetbrains.util

/**
 * Utilities for working with unified diffs produced by OpenCode V2.
 */
object PatchUtil {

    private data class BodyLine(
        val prefix: Char,
        val text: String
    )

    private data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<BodyLine>,
        val oldNoNewline: Boolean,
        val newNoNewline: Boolean
    )

    private val HUNK_HEADER = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")

    /**
     * Reverse-applies a unified diff to [after], producing the original file
     * content. Returns null when the patch cannot be applied safely.
     */
    fun reverseApply(after: String, patch: String): String? {
        if (patch.isBlank()) return null
        val hunks = parseHunks(patch)
        if (hunks.isEmpty()) return null

        var contentLines = splitLines(after).toMutableList()

        for (hunk in hunks.sortedByDescending { it.newStart }) {
            val oldSide = hunk.lines.filter { it.prefix == ' ' || it.prefix == '-' }.map { it.text }
            val newSide = hunk.lines.filter { it.prefix == ' ' || it.prefix == '+' }.map { it.text }

            if (newSide.size != hunk.newCount) return null

            val startIndex = if (hunk.newCount == 0) {
                hunk.newStart.coerceIn(0, contentLines.size)
            } else {
                (hunk.newStart - 1).coerceAtLeast(0)
            }
            val endIndex = startIndex + hunk.newCount

            if (startIndex > contentLines.size || endIndex > contentLines.size) return null
            if (contentLines.subList(startIndex, endIndex) != newSide) return null

            contentLines = (
                contentLines.subList(0, startIndex) +
                    oldSide +
                    contentLines.subList(endIndex, contentLines.size)
                ).toMutableList()
        }

        if (contentLines.isEmpty()) return ""

        val endsWithNewline = hunks.none { it.oldNoNewline }
        return buildString {
            append(contentLines.joinToString("\n"))
            if (endsWithNewline) append('\n')
        }
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split("\n")
        return if (text.endsWith("\n")) lines.dropLast(1) else lines
    }

    private fun parseHunks(patch: String): List<Hunk> {
        val hunks = mutableListOf<Hunk>()
        val rawLines = patch.lines()

        var oldStart = 0
        var oldCount = 0
        var newStart = 0
        var newCount = 0
        var body: MutableList<BodyLine>? = null
        var oldNoNewline = false
        var newNoNewline = false

        fun flush() {
            val current = body
            if (current != null) {
                hunks.add(
                    Hunk(
                        oldStart = oldStart,
                        oldCount = oldCount,
                        newStart = newStart,
                        newCount = newCount,
                        lines = current.toList(),
                        oldNoNewline = oldNoNewline,
                        newNoNewline = newNoNewline
                    )
                )
            }
            body = null
            oldNoNewline = false
            newNoNewline = false
        }

        for (rawLine in rawLines) {
            val line = rawLine.removeSuffix("\r")
            if (line.startsWith("@@")) {
                flush()
                val match = HUNK_HEADER.matchEntire(line) ?: return emptyList()
                oldStart = match.groupValues[1].toIntOrNull() ?: return emptyList()
                oldCount = match.groupValues[2].ifBlank { "1" }.toIntOrNull() ?: return emptyList()
                newStart = match.groupValues[3].toIntOrNull() ?: return emptyList()
                newCount = match.groupValues[4].ifBlank { "1" }.toIntOrNull() ?: return emptyList()
                body = mutableListOf()
                continue
            }

            val current = body ?: continue
            when {
                line.startsWith("\\") -> {
                    val previous = current.lastOrNull()
                    if (previous != null) {
                        if (previous.prefix == '-' || previous.prefix == ' ') oldNoNewline = true
                        if (previous.prefix == '+' || previous.prefix == ' ') newNoNewline = true
                    }
                }
                line.startsWith("+") || line.startsWith("-") || line.startsWith(" ") -> {
                    current.add(BodyLine(line[0], line.substring(1)))
                }
                line.isEmpty() -> {
                    // Ignore blank separators; an empty content line is encoded as a single space.
                }
                else -> {
                    // A new file header or unknown content ends the current hunk.
                    flush()
                }
            }
        }
        flush()

        return hunks
    }
}
