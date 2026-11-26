package io.github.otahelper.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Streams `update_engine_client --follow` in its own su process (never the shared [RootShell], which
 * must stay free for actions). The app cannot kill a root process, so the command runs under
 * `timeout`: an abandoned follower ends by itself and the watcher simply starts the next one.
 */
class EngineFollower(private val log: EventLog, private val io: CoroutineDispatcher = Dispatchers.IO) {
    @Volatile private var mountMaster = true

    /** Calls [onLine] for every output line until the client exits; returns its exit code (negative on failure). */
    suspend fun follow(onLine: (String) -> Unit): Int {
        val argv = if (mountMaster) listOf("su", "-M", "-c", COMMAND) else listOf("su", "-c", COMMAND)
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (e: IOException) {
            log.log("follow: cannot start su: ${e.message}")
            return Shell.CODE_NO_SHELL
        }
        val lines = Channel<String>(Channel.UNLIMITED)
        thread(isDaemon = true, name = "engine-follow") {
            try {
                process.inputStream.bufferedReader().forEachLine { lines.trySend(it) }
            } catch (ignored: IOException) {
                // Stream closed because the watcher stopped.
            } finally {
                lines.close()
            }
        }
        var statusLines = 0
        try {
            for (line in lines) {
                if (EngineStatusParser.parseLine(line) != null) statusLines++
                onLine(line)
            }
            val code = withTimeoutOrNull(EXIT_WAIT_MS) { runInterruptible(io) { process.waitFor() } }
                ?: Shell.CODE_TIMEOUT
            val suFailed = statusLines == 0 && code != 0 && code != TIMEOUT_EXIT
            if (suFailed && mountMaster) {
                log.log("follow: su -M failed (exit $code), falling back to plain su")
                mountMaster = false
            }
            return code
        } finally {
            process.destroy()
        }
    }

    private companion object {
        /** Restart period for the follower; also bounds how long an abandoned one lingers. */
        const val COMMAND = "timeout 300 update_engine_client --follow 2>&1"
        const val TIMEOUT_EXIT = 124
        const val EXIT_WAIT_MS = 5_000L
    }
}
