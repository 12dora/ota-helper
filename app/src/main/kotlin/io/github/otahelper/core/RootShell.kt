package io.github.otahelper.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * A persistent `su` process. Each command runs in a subshell with stdin from /dev/null; its end is
 * detected by a random marker printed on both stdout and stderr, followed by the exit status.
 *
 * KernelSU's su supports `-M` (`--mount-master`, ksud su.rs) which joins init's mount namespace, so
 * /sdcard resolves to the FUSE view and files written there get MediaProvider ownership. Plain `su`
 * is the fallback for other su implementations.
 *
 * A root process cannot be killed by the app, so on timeout the session's pipes are closed and the
 * session is abandoned; the next command starts a fresh one.
 */
class RootShell(
    private val log: EventLog,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Commands tried in order to open a session. */
    private val suCommands: List<List<String>> = SU_VARIANTS,
    /** Accept only sessions running as uid 0. Off only in tests against a plain shell. */
    private val requireRoot: Boolean = true,
) : Shell {
    private val mutex = Mutex()
    private var session: Session? = null

    override suspend fun run(command: String, timeoutMs: Long): ShellResult = mutex.withLock {
        withContext(io) {
            val startedAt = System.nanoTime()
            val current = obtainSession()
            val result = if (current == null) {
                ShellResult(Shell.CODE_NO_SHELL, "", "su unavailable")
            } else {
                execute(current, command, timeoutMs)
            }
            val millis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
            log.log("$ ${summarize(command)} -> ${result.code} (${millis}ms)")
            result
        }
    }

    override suspend fun isRootAvailable(): Boolean {
        val result = run("id -u", ROOT_CHECK_TIMEOUT_MS)
        return isAcceptedUid(result)
    }

    private fun isAcceptedUid(result: ShellResult) = result.ok && (!requireRoot || result.stdout.trim() == "0")

    private suspend fun obtainSession(): Session? {
        session?.let { if (it.alive) return it else it.close() }
        session = null
        for (argv in suCommands) {
            val started = Session.start(argv) ?: continue
            val check = execute(started, "id -u", ROOT_CHECK_TIMEOUT_MS)
            if (isAcceptedUid(check)) {
                session = started
                return started
            }
            started.close()
        }
        log.log("root: su unavailable")
        return null
    }

    private suspend fun execute(target: Session, command: String, timeoutMs: Long): ShellResult {
        val token = "__OTAH_" + UUID.randomUUID().toString().replace("-", "") + "__"
        val script = "(\n$command\n) </dev/null\n" +
            "printf '\\n%s %d\\n' $token \$?\n" +
            "printf '\\n%s\\n' $token >&2\n"
        try {
            target.write(script)
        } catch (e: IOException) {
            log.log("root: write failed: ${e.message}")
            discard(target)
            return ShellResult(Shell.CODE_NO_SHELL, "", e.message.orEmpty())
        }
        // If the caller is cancelled mid-read, the command's output is still in the pipes: the session is
        // out of step and must not be reused.
        var settled = false
        val result = try {
            withTimeoutOrNull(timeoutMs) {
                val out = readUntilMarker(target.stdout, token, requireCode = true)
                val err = readUntilMarker(target.stderr, token, requireCode = false)
                if (out == null || err == null) {
                    ShellResult(Shell.CODE_NO_SHELL, out?.text.orEmpty(), err?.text.orEmpty())
                } else {
                    ShellResult(out.code, out.text, err.text)
                }
            }.also { settled = true }
        } finally {
            if (!settled) {
                log.log("root: cancelled while waiting, discarding shell")
                discard(target)
            }
        }
        if (result == null) {
            log.log("root: timed out after ${timeoutMs}ms, discarding shell")
            discard(target)
            return ShellResult(Shell.CODE_TIMEOUT, "", "timeout")
        }
        if (result.code == Shell.CODE_NO_SHELL) discard(target)
        return result
    }

    private fun discard(target: Session) {
        target.close()
        if (session === target) session = null
    }

    /** One log line per command: scripts are folded onto a line and capped. */
    private fun summarize(command: String): String {
        val folded = command.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")
        return if (folded.length > LOG_COMMAND_CHARS) folded.take(LOG_COMMAND_CHARS) + "…" else folded
    }

    private class Captured(val text: String, val code: Int)

    /**
     * Reads lines until the marker; null when the stream ended first. With [requireCode] a marker without
     * a readable exit status yields [Shell.CODE_NO_SHELL] (fail closed) instead of success.
     */
    private suspend fun readUntilMarker(channel: Channel<String>, token: String, requireCode: Boolean): Captured? {
        val lines = ArrayList<String>()
        while (true) {
            val line = channel.receiveCatching().getOrNull() ?: return null
            if (line.startsWith(token)) {
                val parsed = line.removePrefix(token).trim().toIntOrNull()
                val code = parsed ?: if (requireCode) Shell.CODE_NO_SHELL else 0
                // The marker is preceded by an injected newline: drop it when it produced an empty line.
                if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
                return Captured(lines.joinToString("\n"), code)
            }
            lines += line
        }
    }

    private class Session(private val process: Process) {
        val stdout = Channel<String>(Channel.UNLIMITED)
        val stderr = Channel<String>(Channel.UNLIMITED)
        private val writer = process.outputStream.bufferedWriter()

        init {
            pump(process.inputStream, stdout)
            pump(process.errorStream, stderr)
        }

        val alive: Boolean get() = process.isAlive

        fun write(text: String) {
            writer.write(text)
            writer.flush()
        }

        fun close() {
            try {
                writer.close()
            } catch (ignored: IOException) {
                // Already closed by the other side.
            }
            process.destroy()
            stdout.close()
            stderr.close()
        }

        companion object {
            fun start(argv: List<String>): Session? = try {
                Session(ProcessBuilder(argv).start())
            } catch (ignored: IOException) {
                null
            }

            private fun pump(stream: InputStream, channel: Channel<String>) {
                thread(isDaemon = true, name = "root-shell-pump") {
                    try {
                        stream.bufferedReader().forEachLine { channel.trySend(it) }
                    } catch (ignored: IOException) {
                        // Stream closed: the session is gone.
                    } finally {
                        channel.close()
                    }
                }
            }
        }
    }

    private companion object {
        const val ROOT_CHECK_TIMEOUT_MS = 15_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val LOG_COMMAND_CHARS = 200
        val SU_VARIANTS = listOf(listOf("su", "-M"), listOf("su"))
    }
}
