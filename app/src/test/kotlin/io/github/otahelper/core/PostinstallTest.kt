package io.github.otahelper.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** update_engine activity after the patch: optional postinstall versus a new payload. */
class PostinstallTest : EngineTestBase() {
    @Test
    fun `optional postinstall after the patch keeps the flow patched and delays the reboot`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        assertEquals(sha1Of("ksu:stock:V2"), store.record.patchedSha1)

        device.engine("FINALIZING", 5, "0")
        engine.refresh()
        assertEquals(Phase.PATCHED, engine.status.value.phase)
        assertEquals(FlowIntent.PATCHED, store.record.intent)
        assertTrue(log.lines.none { it.startsWith("intent PATCHED -> PREPARED") })
        engine.onEngineUpdate(EngineUpdate(EngineStatus.FINALIZING, 0.5f))
        assertEquals(Phase.PATCHED, engine.status.value.phase)
        assertEquals("UPDATE_RUNNING", engine.reboot().message)
        assertFalse(device.rebooted)

        device.engine("UPDATED_NEED_REBOOT", 6, "1")
        assertTrue(engine.reboot().ok)
        assertTrue(device.rebooted)
        assertEquals(1, device.shell.count("ksud boot-patch"))
    }

    @Test
    fun `a rewritten inactive slot after the patch is no longer patched`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        // A new install starts writing the inactive slot again.
        device.write(partB, "stock:V3")
        device.engine("DOWNLOADING", 3, "0.1")
        engine.refresh()
        assertEquals(Phase.INSTALLING, engine.status.value.phase)
        assertEquals(FlowIntent.PREPARED, store.record.intent)
        assertEquals("", store.record.patchedSha1)
    }

    @Test
    fun `watcher lines after the patch never bring PATCHED back for a new install`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        assertEquals(Phase.PATCHED, engine.status.value.phase)

        engine.onEngineUpdate(EngineUpdate(EngineStatus.IDLE, 0f))
        assertEquals(Phase.PREPARED, engine.status.value.phase)
        engine.onEngineUpdate(EngineUpdate(EngineStatus.DOWNLOADING, 0.1f))
        assertEquals(Phase.INSTALLING, engine.status.value.phase)
        assertEquals(true, platform.watcher.last())
    }

    @Test
    fun `a new download straight after the patch is installing with the watcher running`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        engine.onEngineUpdate(EngineUpdate(EngineStatus.DOWNLOADING, 0.1f))
        assertEquals(Phase.INSTALLING, engine.status.value.phase)
        assertEquals(true, platform.watcher.last())
        // A refresh while the old patched image is still on the slot agrees.
        device.engine("DOWNLOADING", 3, "0.1")
        engine.refresh()
        assertEquals(Phase.INSTALLING, engine.status.value.phase)
        assertEquals("", store.record.patchedSha1)
    }

    @Test
    fun `postinstall line from the watcher keeps PATCHED`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        engine.onEngineUpdate(EngineUpdate(EngineStatus.FINALIZING, 0.3f))
        assertEquals(Phase.PATCHED, engine.status.value.phase)
    }

    @Test
    fun `inactive slot is not read when the engine status cannot use it`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        device.engine("IDLE", 0)
        val before = device.shell.count("cat '$partB' > img")
        engine.refresh()
        assertEquals(before, device.shell.count("cat '$partB' > img"))
        assertEquals(Phase.PREPARED, engine.status.value.phase)
        assertEquals(FlowIntent.PREPARED, store.record.intent)
        assertEquals("", store.record.patchedSha1)
    }

    @Test
    fun `a v1_0_0 record without the patched hash recovers through postinstall and reboots`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        store.record = store.record.copy(patchedSha1 = "")

        device.engine("FINALIZING", 5, "0")
        engine.refresh()
        assertEquals(Phase.INSTALLING, engine.status.value.phase)
        assertEquals(1, device.shell.count("ksud boot-patch"))

        device.engine("UPDATED_NEED_REBOOT", 6, "1")
        engine.refresh()
        assertEquals(Phase.PATCHED, engine.status.value.phase)
        assertTrue(engine.reboot().ok)
        assertEquals(1, device.shell.count("ksud boot-patch"))
    }

    @Test
    fun `the patched hash is gone after the reboot finishes`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        assertTrue(store.record.patchedSha1.isNotEmpty())
        device.slot = "_b"
        device.version = "V2"
        device.engine("IDLE", 0)
        platform.boot = "boot-2"
        engine.refresh()
        assertEquals(FlowRecord(), store.record)
    }
}
