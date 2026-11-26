package io.github.otahelper.core

/** Result of one shell command. [code] is the command's exit status, or a negative [Shell] code on failure. */
data class ShellResult(val code: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = code == 0

    /** Non-blank stdout lines, trimmed. */
    val lines: List<String> get() = stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
}

/**
 * A root shell. Implementations run one command at a time; concurrent callers are serialized.
 * Commands never read stdin. Failures (timeout, shell death, su missing) are reported through
 * negative [ShellResult.code] values instead of exceptions.
 */
interface Shell {
    suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult

    /** True when a root shell can be opened and runs as uid 0. */
    suspend fun isRootAvailable(): Boolean

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** su could not be started or died before the command completed. */
        const val CODE_NO_SHELL = -1

        /** The command did not finish within its timeout; the shell was discarded. */
        const val CODE_TIMEOUT = -2
    }
}

/** Single-quotes [value] for POSIX sh. */
fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
