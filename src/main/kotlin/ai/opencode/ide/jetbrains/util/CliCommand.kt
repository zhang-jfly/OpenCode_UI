package ai.opencode.ide.jetbrains.util

/**
 * Helpers for building OpenCode CLI command lines.
 */
object CliCommand {

    /** Username required by the OpenCode 2.x Basic Auth implementation. */
    const val V2_USERNAME = "opencode"

    /**
     * Builds the `--server` URL used by OpenCode 2.x clients.
     *
     * Credentials are embedded as URL userinfo instead of relying on shell
     * specific environment assignment (`set VAR=... && cmd`), which breaks in
     * PowerShell and Git Bash and cannot carry characters such as `&`.
     *
     * Example: `buildServerUrl("127.0.0.1", 4096, password = "p@ss")`
     * returns `http://opencode:p%40ss@127.0.0.1:4096`.
     */
    fun buildServerUrl(
        host: String,
        port: Int,
        username: String = V2_USERNAME,
        password: String? = null
    ): String {
        val authorityHost = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val credentials = if (password.isNullOrEmpty()) {
            ""
        } else {
            "${encodeUserInfo(username)}:${encodeUserInfo(password)}@"
        }
        return "http://$credentials$authorityHost:$port"
    }

    /**
     * Percent-encodes a value for use in the userinfo part of a URL.
     *
     * Unreserved characters (RFC 3986) are kept as-is and everything else is
     * encoded as uppercase UTF-8 percent escapes. A space becomes `%20`, never
     * `+`, because `+` is not decoded to a space in userinfo.
     */
    fun encodeUserInfo(value: String): String {
        val builder = StringBuilder(value.length)
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char in UNRESERVED_CHARS) {
                builder.append(char)
            } else {
                builder.append('%')
                builder.append(HEX_DIGITS[code ushr 4])
                builder.append(HEX_DIGITS[code and 0x0F])
            }
        }
        return builder.toString()
    }

    private const val UNRESERVED_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX_DIGITS = "0123456789ABCDEF"
}
