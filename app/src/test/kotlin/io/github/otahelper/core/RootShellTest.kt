package io.github.otahelper.core

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [RootShell] framing and recovery against a plain /bin/sh standing in for su. */
class RootShellTest {
    private val executor = Executors.newCachedThreadPool()
    private val log = MemoryLog()
    private val shell = RootShell(
        log,
        executor.asCoroutineDispatcher(),
        suCommands = listOf(listOf("/bin/sh")),
        requireRoot = false,
    )

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `captures stdout, stderr and exit code separately`() = runBlocking {
        val result = shell.run("echo out; echo err >&2; exit 3")
        assertEquals(ShellResult(3, "out", "err"), result)
    }

    @Test
    fun `keeps output without a trailing newline and empty output`() = runBlocking {
        assertEquals("a\nb", shell.run("printf 'a\\nb'").stdout)
        assertEquals("a\n", shell.run("printf 'a\\n\\n'").stdout)
        assertEquals("", shell.run("true").stdout)
        assertEquals(1, shell.run("false").code)
    }

    @Test
    fun `commands do not leak state or read stdin`() = runBlocking {
        shell.run("cd /; x=1")
        assertEquals("", shell.run("echo \$x").stdout)
        assertEquals(0, shell.run("cat").code)
        assertEquals("ok", shell.run("echo ok").stdout)
    }

    @Test
    fun `timeout discards the session and the next command works`() = runBlocking {
        val slow = shell.run("sleep 5; echo late", timeoutMs = 300)
        assertEquals(Shell.CODE_TIMEOUT, slow.code)
        assertEquals(ShellResult(0, "next", ""), shell.run("echo next"))
    }

    @Test
    fun `a dying shell is reported and replaced`() = runBlocking {
        val died = shell.run("kill -9 \$\$")
        assertEquals(Shell.CODE_NO_SHELL, died.code)
        assertEquals("alive", shell.run("echo alive").stdout)
    }

    @Test
    fun `cancelled command does not leave its output for the next one`() = runBlocking {
        val job = launch { shell.run("sleep 1; echo stale") }
        delay(200)
        job.cancel()
        job.join()
        assertEquals(ShellResult(0, "fresh", ""), shell.run("echo fresh"))
        delay(1_200)
        assertEquals(ShellResult(0, "again", ""), shell.run("echo again"))
    }

    @Test
    fun `missing su means no root`() = runBlocking {
        val none = RootShell(log, executor.asCoroutineDispatcher(), suCommands = listOf(listOf("/nonexistent/su")))
        assertFalse(none.isRootAvailable())
        assertEquals(Shell.CODE_NO_SHELL, none.run("echo x").code)
    }

    @Test
    fun `a non-root shell is not accepted as root`() = runBlocking {
        val user = RootShell(log, executor.asCoroutineDispatcher(), suCommands = listOf(listOf("/bin/sh")))
        // Only a uid 0 session is accepted; the test JVM normally runs as a regular user.
        val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().use { it.readText().trim() }
        assertEquals(uid == "0", user.isRootAvailable())
        assertTrue(shell.isRootAvailable())
    }

    @Test
    fun `every command is logged on one line`() = runBlocking {
        shell.run("echo a\necho b")
        assertTrue(log.lines.any { it.startsWith("$ echo a; echo b -> 0") })
    }
}
