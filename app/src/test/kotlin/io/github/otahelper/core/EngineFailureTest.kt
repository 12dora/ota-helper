package io.github.otahelper.core

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Failure, retry and race paths of the engine. */
class EngineFailureTest : EngineTestBase() {
    @Test
    fun `prepare is refused when the stock backup has another size or is patched`() = runBlocking {
        device.sizes[device.backupOf("stock:V1")] = FakeDevice.IMAGE_SIZE / 2
        assertEquals("BACKUP_INVALID", engine.prepare().message)
        device.write(device.backupOf("stock:V1"), "ksu:stock:V1")
        assertEquals("BACKUP_INVALID", engine.prepare().message)
        assertEquals(0, flashes())
    }

    @Test
    fun `a backup copy that does not verify never replaces the target`() = runBlocking {
        device.corruptSaves = true
        assertEquals("SAVE_FAILED $root/V1", engine.prepare().message)
        assertTrue(device.files.keys.none { it.startsWith("$root/V1/") })
        assertEquals(0, flashes())
    }

    @Test
    fun `cancel failing before the flash leaves the device prepared`() = runBlocking {
        assertTrue(engine.prepare().ok)
        device.patchFailsBeforeFlash = true
        assertEquals("FLASH_FAILED", engine.cancel().message)
        assertEquals("stock:V1", device.files[partA])
        assertEquals(Phase.PREPARED, engine.status.value.phase)
    }

    @Test
    fun `cancel that does not verify fails the current slot`() = runBlocking {
        assertTrue(engine.prepare().ok)
        device.patchWrites = "garbage"
        assertEquals("CURRENT_SLOT_FAILED $root/V1", engine.cancel().message)
        assertEquals(Phase.FAILED, engine.status.value.phase)
        assertEquals(1, device.shell.count("ksud boot-patch"))
    }

    @Test
    fun `cancel is refused while update_engine cannot be read`() = runBlocking {
        assertTrue(engine.prepare().ok)
        device.engineSilent = true
        assertEquals("ENGINE_UNKNOWN", engine.cancel().message)
        assertEquals(0, device.shell.count("ksud boot-patch"))
    }

    @Test
    fun `patch stops when update_engine changes right before the flash`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        val start = device.engineQueries
        // Query start+1 is the action's own reading; start+2 is the re-check right before flashing.
        device.beforeEngineQuery = { n -> if (n == start + 2) device.engine("CLEANUP_PREVIOUS_UPDATE", 11) }
        assertEquals("NOT_INSTALLED", engine.patch().message)
        assertEquals(0, device.shell.count("ksud boot-patch"))
    }

    @Test
    fun `patch is refused when the new stock image cannot be saved`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        device.failSaves = true
        assertEquals("SAVE_FAILED $root/pending", engine.patch().message)
        assertEquals(0, device.shell.count("ksud boot-patch"))
    }

    @Test
    fun `reboot failure is reported and a vanished shell counts as rebooting`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        device.rebootCode = 1
        assertEquals("REBOOT_FAILED", engine.reboot().message)
        device.rebootCode = Shell.CODE_NO_SHELL
        assertTrue(engine.reboot().ok)
    }

    @Test
    fun `lost root after the update rolled back points at the old build's patched image`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        platform.boot = "boot-2"
        platform.slot = "_a"
        device.shell.root = false
        engine.refresh()
        assertEquals(Phase.NO_ROOT, engine.status.value.phase)
        assertEquals("ROOT_LOST $root/V1/init_boot_ksu.img", engine.status.value.error)
    }

    @Test
    fun `a refused watcher start is retried on the next refresh`() = runBlocking {
        platform.refuseStarts = true
        assertTrue(engine.prepare().ok)
        val refused = platform.watcher.count { it }
        assertTrue(refused >= 1)
        platform.refuseStarts = false
        engine.refresh()
        assertTrue(platform.watcher.count { it } > refused)
        assertEquals(true, platform.watcher.last())
    }

    @Test
    fun `a failed backup promotion after reboot is retried`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        device.slot = "_b"
        device.version = "V2"
        device.engine("IDLE", 0)
        platform.boot = "boot-2"
        device.failPromote = true
        engine.refresh()
        assertEquals(FlowIntent.PATCHED, store.record.intent)
        assertTrue(platform.events.isEmpty())
        device.failPromote = false
        engine.refresh()
        assertEquals(FlowIntent.NONE, store.record.intent)
        assertEquals(listOf("updated V2"), platform.events)
        assertEquals("ksu:stock:V2", device.files["$root/V2/init_boot_ksu.img"])
    }

    @Test
    fun `an old action error gives way to a newer device problem`() = runBlocking {
        device.files.remove(device.backupOf("stock:V1"))
        assertEquals("BACKUP_MISSING", engine.prepare().message)
        assertEquals("BACKUP_MISSING", engine.status.value.error)
        device.shell.root = false
        engine.refresh()
        assertEquals(Phase.NO_ROOT, engine.status.value.phase)
        assertEquals("NO_ROOT", engine.status.value.error)
        device.shell.root = true
        engine.refresh()
        assertNull(engine.status.value.error)
    }

    @Test
    fun `watcher lines during cancel cannot bring back the prepared state`() = runBlocking {
        assertTrue(engine.prepare().ok)
        val cancel = async { engine.cancel() }
        val spammer = async(executor.asCoroutineDispatcher()) {
            while (!cancel.isCompleted) {
                engine.onEngineUpdate(EngineUpdate(EngineStatus.DOWNLOADING, 0.1f))
                engine.onEngineUpdate(EngineUpdate(EngineStatus.IDLE, 0f))
                delay(1)
            }
        }
        assertTrue(cancel.await().ok)
        spammer.await()
        engine.onEngineUpdate(EngineUpdate(EngineStatus.DOWNLOADING, 0.1f))
        assertEquals(Phase.IDLE, engine.status.value.phase)
        assertEquals(false, platform.watcher.last())
    }

    @Test
    fun `manual patch racing the automatic one flashes once and reports no failure`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        engine.refresh()
        engine.onEngineUpdate(EngineUpdate(EngineStatus.UPDATED_NEED_REBOOT, 1f))
        val manual = engine.patch()
        waitFor { engine.status.value.phase == Phase.PATCHED && !engine.busy.value }
        delay(SETTLE_MS)
        assertEquals(1, device.shell.count("ksud boot-patch"))
        assertTrue(platform.events.none { it.startsWith("autopatch failed") }, platform.events.toString())
        assertTrue(manual.ok || manual.message == "WRONG_PHASE" || manual.message == "NOT_INSTALLED")
        assertNull(engine.status.value.error?.takeIf { manual.ok })
    }
}
