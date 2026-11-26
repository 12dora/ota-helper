package io.github.otahelper.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Streams `update_engine_client --follow` in its own su process (never the shared [RootShell], which
 * must stay free for actions).
 *
 * The app cannot signal a root process, so the script ties the client's life to its stdin: closing our
 * end of the pipe (on stop, or when the app process dies) makes a helper `cat` see EOF and kill the
 * client (verified on device: no client is left behind). Process start and teardown run on [io].
 */
class EngineFollower(private val log: EventLog, private val io: CoroutineDispatcher = Dispatchers.IO) {
    @Volatile private var mountMaster = true

    /** Calls [onLine] for every output line until the client exits; returns its exit code (negative on failure). */
    suspend fun follow(onLine: (String) -> Unit): Int = withContext(io) {
        val argv = if (mountMaster) listOf("su", "-M", "-c", SCRIPT) else listOf("su", "-c", SCRIPT)
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (e: IOException) {
            log.log("follow: cannot start su: ${e.message}")
            return@withContext Shell.CODE_NO_SHELL
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
            val code = withTimeoutOrNull(EXIT_WAIT_MS) { runInterruptible { process.waitFor() } }
                ?: Shell.CODE_TIMEOUT
            val suFailed = statusLines == 0 && code != 0
            if (suFailed && mountMaster) {
                log.log("follow: su -M failed (exit $code), falling back to plain su")
                mountMaster = false
            }
            code
        } finally {
            // EOF on stdin stops the client; closing stdout unblocks the reader thread.
            closeQuietly(process.outputStream)
            closeQuietly(process.inputStream)
            process.destroy()
        }
    }

    private fun closeQuietly(stream: Closeable) {
        try {
            stream.close()
        } catch (ignored: IOException) {
            // Already closed.
        }
    }

    private companion object {
        /** Client in the background; a helper waits for EOF on the original stdin (fd 3) and kills it. */
        val SCRIPT = """
            exec 3<&0
            update_engine_client --follow 2>&1 & p=$!
            ( cat <&3 >/dev/null; kill ${'$'}p 2>/dev/null ) >/dev/null 2>&1 & w=$!
            exec 3<&-
            wait ${'$'}p; c=$?
            kill ${'$'}w 2>/dev/null
            exit ${'$'}c
        """.trimIndent()
        const val EXIT_WAIT_MS = 5_000L
    }
}
