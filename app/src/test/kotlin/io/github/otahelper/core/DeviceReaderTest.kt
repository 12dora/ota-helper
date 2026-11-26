package io.github.otahelper.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceReaderTest {
    private val shell = FakeShell()
    private val reader = DeviceReader(shell, MemoryLog())
    private val sha = "a".repeat(40)

    private fun answerInspect(vararg lines: String) {
        shell.on("> img") { ok(lines.joinToString("\n")) }
    }

    @Test
    fun `probe parses the device answer`() = runBlocking {
        shell.on("getprop ro.build.ab_update") {
            ok(
                """
                ab=true
                slot=_b
                version=OS4.0.5.0.XPACNXM
                ksud=ksud 3.3.1 (uapi: 5)
                partition=init_boot
                lkm=true
                magiskboot=1
                dev=init_boot_a
                dev=init_boot_b
                garbage line
                =no key
                """.trimIndent(),
            )
        }
        val probe = assertNotNull(reader.probe())
        assertTrue(probe.abUpdate)
        assertEquals("_b", probe.slotSuffix)
        assertEquals("OS4.0.5.0.XPACNXM", probe.version)
        assertEquals("3.3.1", probe.ksudVersion)
        assertEquals(true, probe.lkm)
        assertEquals(setOf("init_boot_a", "init_boot_b"), probe.devices)
        assertNull(probe.unsupportedReason())
    }

    @Test
    fun `probe without ksud or lkm info`() = runBlocking {
        shell.on("getprop ro.build.ab_update") { ok("ab=true\nslot=_a\nversion=V\nlkm=maybe") }
        val probe = assertNotNull(reader.probe())
        assertNull(probe.ksudVersion)
        assertNull(probe.lkm)
        assertFalse(probe.magiskboot)
        assertEquals(Msg.NO_KERNELSU, probe.unsupportedReason()?.msg)
    }

    @Test
    fun `failed probe is null`() = runBlocking {
        shell.on("getprop ro.build.ab_update") { failed() }
        assertNull(reader.probe())
    }

    @Test
    fun `inspect recognizes patched and stock ramdisks`() = runBlocking {
        answerInspect(
            "SIZE=8388608",
            "SHA=$sha  img",
            "UNPACK=0",
            "RAMDISK=1",
            "KSU=0",
            "INIT=0",
            "MARKER=${"b".repeat(40)}",
        )
        assertEquals(ImageInfo(BootImageState.PATCHED, sha, 8_388_608, "b".repeat(40)), reader.inspect("/x"))
    }

    @Test
    fun `inspect is strict about everything else`() = runBlocking {
        val cases = listOf(
            // No ramdisk extracted.
            listOf("SIZE=1", "SHA=$sha  img", "UNPACK=0"),
            // Unpack failed.
            listOf("SIZE=1", "SHA=$sha  img", "UNPACK=1", "RAMDISK=1", "KSU=1", "INIT=0"),
            // Neither kernelsu.ko nor init.
            listOf("SIZE=1", "SHA=$sha  img", "UNPACK=0", "RAMDISK=1", "KSU=1", "INIT=1"),
            // Unreadable hash.
            listOf("SIZE=1", "SHA=xyz  img", "UNPACK=0", "RAMDISK=1", "KSU=0", "INIT=0"),
            // Nothing at all (copy failed).
            emptyList(),
        )
        cases.forEach { lines ->
            val local = FakeShell().apply { on("> img") { ok(lines.joinToString("\n")) } }
            val info = DeviceReader(local, MemoryLog()).inspect("/x")
            assertEquals(BootImageState.UNKNOWN, info.state, lines.toString())
        }
    }

    @Test
    fun `inspect drops a malformed marker and keeps a stock image`() = runBlocking {
        answerInspect("SIZE=1", "SHA=$sha  img", "UNPACK=0", "RAMDISK=1", "KSU=1", "INIT=0", "MARKER=nothex")
        val info = reader.inspect("/x")
        assertEquals(BootImageState.STOCK, info.state)
        assertNull(info.stockSha1)
    }

    @Test
    fun `hashes are null when the command fails or prints garbage`() = runBlocking {
        shell.on("^sha1sum '/ok'$") { ok("$sha  /ok") }
        shell.on("^sha1sum '/bad'$") { ok("not a hash  /bad") }
        shell.on("^sha1sum") { failed() }
        shell.on("head -c 4 '/missing'") { failed() }
        shell.on("head -c 4 '/ok'") { ok("$sha  -") }
        assertEquals(sha, reader.sha1("/ok"))
        assertNull(reader.sha1("/bad"))
        assertNull(reader.sha1("/gone"))
        assertNull(reader.sha1Prefix("/missing", 4))
        assertEquals(sha, reader.sha1Prefix("/ok", 4))
        assertTrue(shell.commands.any { it.startsWith("set -o pipefail; head -c 4") })
    }

    @Test
    fun `engine status without a status line is null`() = runBlocking {
        shell.on("update_engine_client") { ok("Waiting for update_engine service...") }
        assertNull(reader.engineStatus())
    }

    @Test
    fun `engine status takes the last line`() = runBlocking {
        shell.on("update_engine_client") {
            ok(
                "onStatusUpdate(UPDATE_STATUS_IDLE (0), 0)\n" +
                    "onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 0.25)",
            )
        }
        assertEquals(EngineUpdate(EngineStatus.DOWNLOADING, 0.25f), reader.engineStatus())
    }
}
