package io.github.otahelper.core

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtaEngineTest {
    private val device = FakeDevice().apply { installPatchedV1() }
    private val store = MemoryStore()
    private val platform = FakePlatform()
    private val log = MemoryLog()
    private val executor = Executors.newSingleThreadExecutor()
    private val engine = OtaEngine(device.shell, store, log, platform, executor.asCoroutineDispatcher())

    private val root = Settings.DEFAULT_BACKUP_DIR
    private val partA = device.part("_a")
    private val partB = device.part("_b")

    @AfterEach
    fun tearDown() {
        engine.scope.cancel()
        executor.shutdownNow()
    }

    /** The user installs build V2 in the system updater; update_engine wrote stock V2 to slot B. */
    private fun installUpdate() {
        device.write(partB, "stock:V2")
        device.engine("UPDATED_NEED_REBOOT", 6, "1")
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(WAIT_MS) {
        while (!condition()) delay(POLL_MS)
    }

    private fun flashes() = device.shell.count("ksud boot-(restore|patch)")

    @Test
    fun `refresh on a patched device is idle and does not start update_engine`() = runBlocking {
        engine.refresh()
        val status = engine.status.value
        assertEquals(Phase.IDLE, status.phase)
        assertEquals("V1", status.version)
        assertEquals("A", status.slot)
        assertEquals("init_boot", status.partition)
        assertEquals(BootImageState.PATCHED, status.bootImage)
        assertEquals("KernelSU 3.3.1", status.rootManager)
        assertNull(status.error)
        assertEquals(0, device.shell.count("update_engine_client"))
        assertEquals(listOf(false), platform.watcher)
    }

    @Test
    fun `no root is reported`() = runBlocking {
        device.shell.root = false
        engine.refresh()
        assertEquals(Phase.NO_ROOT, engine.status.value.phase)
        assertEquals("NO_ROOT", engine.status.value.error)
    }

    @Test
    fun `non-LKM KernelSU is unsupported`() = runBlocking {
        device.lkm = false
        engine.refresh()
        assertEquals(Phase.UNSUPPORTED, engine.status.value.phase)
        assertEquals("NOT_LKM", engine.status.value.error)
        assertFalse(engine.prepare().ok)
        assertEquals(0, flashes())
    }

    @Test
    fun `prepare saves both images and restores stock`() = runBlocking {
        val result = engine.prepare()
        assertTrue(result.ok, result.message)
        assertEquals("PREPARE_DONE", result.message)
        assertEquals("stock:V1", device.files[partA])
        assertEquals("stock:V0", device.files[partB])
        assertEquals("stock:V1", device.files["$root/V1/init_boot_stock.img"])
        assertEquals("ksu:stock:V1", device.files["$root/V1/init_boot_ksu.img"])
        assertEquals(FlowIntent.PREPARED, store.record.intent)
        assertEquals(sha1Of("stock:V1"), store.record.stockSha1)
        assertEquals(Phase.PREPARED, engine.status.value.phase)
        assertEquals(BootImageState.STOCK, engine.status.value.bootImage)
        assertEquals(true, platform.watcher.last())
        assertEquals(1, flashes())
        val backup = engine.backups.value.single()
        assertEquals("V1", backup.version)
        assertEquals("$root/V1/init_boot_stock.img", backup.stockPath)
        assertEquals("$root/V1/init_boot_ksu.img", backup.patchedPath)
    }

    @Test
    fun `prepare is refused when the stock backup is missing`() = runBlocking {
        device.files.remove(device.backupOf("stock:V1"))
        val result = engine.prepare()
        assertFalse(result.ok)
        assertEquals("BACKUP_MISSING", result.message)
        assertEquals(0, flashes())
        assertEquals("ksu:stock:V1", device.files[partA])
        assertEquals(FlowIntent.NONE, store.record.intent)
        assertEquals(Phase.IDLE, engine.status.value.phase)
        assertEquals("BACKUP_MISSING", engine.status.value.error)
    }

    @Test
    fun `prepare is refused when the stock backup does not match its hash`() = runBlocking {
        device.write(device.backupOf("stock:V1"), "stock:tampered")
        val result = engine.prepare()
        assertEquals("BACKUP_INVALID", result.message)
        assertEquals(0, flashes())
        assertEquals("ksu:stock:V1", device.files[partA])
    }

    @Test
    fun `prepare is refused when the backup cannot be saved`() = runBlocking {
        device.failSaves = true
        val result = engine.prepare()
        assertEquals("SAVE_FAILED $root/V1", result.message)
        assertEquals(0, flashes())
    }

    @Test
    fun `prepare stops when the partition does not verify after flashing`() = runBlocking {
        device.restoreWrites = "garbage"
        val result = engine.prepare()
        assertFalse(result.ok)
        assertEquals("CURRENT_SLOT_FAILED $root/V1", result.message)
        assertEquals(1, flashes())
        assertEquals(0, device.shell.count("ksud boot-patch"))
        assertEquals("CURRENT_SLOT_FAILED $root/V1", engine.status.value.error)
        assertEquals(Phase.FAILED, engine.status.value.phase)
        // Nothing else is flashed later, on its own or on request.
        engine.refresh()
        assertEquals(Phase.FAILED, engine.status.value.phase)
        assertEquals("WRONG_PHASE", engine.cancel().message)
        assertEquals(1, flashes())
    }

    @Test
    fun `failed restore that left the partition untouched heals back to idle`() = runBlocking {
        device.restoreWrites = "ksu:stock:V1"
        val result = engine.prepare()
        assertEquals("FLASH_FAILED", result.message)
        assertEquals(Phase.IDLE, engine.status.value.phase)
        assertEquals(FlowIntent.NONE, store.record.intent)
    }

    @Test
    fun `cancel patches the current slot again`() = runBlocking {
        assertTrue(engine.prepare().ok)
        val result = engine.cancel()
        assertTrue(result.ok, result.message)
        assertEquals("CANCEL_DONE", result.message)
        assertEquals("ksu:stock:V1", device.files[partA])
        assertEquals(FlowIntent.NONE, store.record.intent)
        assertEquals(Phase.IDLE, engine.status.value.phase)
        assertEquals(false, platform.watcher.last())
        assertEquals(1, device.shell.count("ksud boot-patch -f --partition"))
        assertEquals(0, device.shell.count("ksud boot-patch -f -u"))
    }

    @Test
    fun `cancel is refused while the update installs`() = runBlocking {
        assertTrue(engine.prepare().ok)
        device.engine("DOWNLOADING", 3, "0.5")
        val result = engine.cancel()
        assertEquals("UPDATE_RUNNING", result.message)
        assertEquals(0, device.shell.count("ksud boot-patch"))
        assertEquals(Phase.INSTALLING, engine.status.value.phase)
        assertEquals(0.425f, engine.status.value.progress!!, 0.0001f)
    }

    @Test
    fun `cancel is refused once the update is installed`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertEquals("UPDATE_INSTALLED", engine.cancel().message)
        assertEquals(0, device.shell.count("ksud boot-patch"))
    }

    @Test
    fun `patch backs up the new stock image and patches the inactive slot`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        engine.refresh()
        assertEquals(Phase.INSTALLED, engine.status.value.phase)
        val result = engine.patch()
        assertTrue(result.ok, result.message)
        assertEquals("PATCH_DONE", result.message)
        assertEquals("ksu:stock:V2", device.files[partB])
        assertEquals("stock:V1", device.files[partA])
        assertEquals("stock:V2", device.files["$root/pending/init_boot_stock.img"])
        assertEquals("ksu:stock:V2", device.files["$root/pending/init_boot_ksu.img"])
        assertEquals(FlowIntent.PATCHED, store.record.intent)
        assertEquals(Phase.PATCHED, engine.status.value.phase)
        assertEquals(false, platform.watcher.last())
        assertEquals(1, device.shell.count("ksud boot-patch -f -u --partition 'init_boot'"))
    }

    @Test
    fun `patch is refused until update_engine reports UPDATED_NEED_REBOOT`() = runBlocking {
        assertTrue(engine.prepare().ok)
        device.write(partB, "stock:V2")
        listOf("DOWNLOADING" to 3, "FINALIZING" to 5, "IDLE" to 0).forEach { (status, code) ->
            device.engine(status, code, "0.9")
            val result = engine.patch()
            assertFalse(result.ok)
            assertEquals("NOT_INSTALLED", result.message)
        }
        assertEquals(0, device.shell.count("ksud boot-patch"))
        assertEquals("stock:V2", device.files[partB])
    }

    @Test
    fun `patch is refused on an idle device`() = runBlocking {
        installUpdate()
        assertEquals("NOT_INSTALLED", engine.patch().message)
        assertEquals(0, flashes())
    }

    @Test
    fun `patch stops when the inactive slot does not verify and reboot is refused`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        device.patchWrites = "garbage"
        val result = engine.patch()
        assertFalse(result.ok)
        assertEquals("NEW_SLOT_FAILED $root/pending", result.message)
        assertEquals(1, device.shell.count("ksud boot-patch"))
        assertNull(device.files["$root/pending/init_boot_ksu.img"])
        assertEquals("stock:V2", device.files["$root/pending/init_boot_stock.img"])
        assertEquals(FlowIntent.PREPARED, store.record.intent)
        assertEquals(Phase.FAILED, engine.status.value.phase)
        assertEquals(false, platform.watcher.last())

        assertEquals("WRONG_PHASE", engine.reboot().message)
        assertFalse(device.rebooted)
        assertEquals("WRONG_PHASE", engine.patch().message)
        engine.refresh()
        assertEquals(Phase.FAILED, engine.status.value.phase)
        assertEquals("WRONG_PHASE", engine.patch().message)
        assertEquals(1, device.shell.count("ksud boot-patch"))
    }

    @Test
    fun `ksud failing before the flash is reported as unchanged, not as a failed verification`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        device.patchFailsBeforeFlash = true
        assertEquals("FLASH_FAILED", engine.patch().message)
        assertEquals("stock:V2", device.files[partB])
        assertEquals(Phase.INSTALLED, engine.status.value.phase)
        assertEquals("FLASH_FAILED", engine.status.value.error)
    }

    @Test
    fun `patch verifies a patched image shorter than the partition`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        device.patchedSize = FakeDevice.IMAGE_SIZE / 2
        val result = engine.patch()
        assertTrue(result.ok, result.message)
        assertEquals(Phase.PATCHED, engine.status.value.phase)
    }

    @Test
    fun `reboot then finish promotes the pending backup`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        val reboot = engine.reboot()
        assertTrue(reboot.ok, reboot.message)
        assertTrue(device.rebooted)

        device.slot = "_b"
        device.version = "V2"
        device.engine("IDLE", 0)
        platform.boot = "boot-2"
        engine.refresh()

        assertEquals(Phase.IDLE, engine.status.value.phase)
        assertEquals("B", engine.status.value.slot)
        assertEquals("stock:V2", device.files["$root/V2/init_boot_stock.img"])
        assertEquals("ksu:stock:V2", device.files["$root/V2/init_boot_ksu.img"])
        assertTrue(device.files.keys.none { it.startsWith("$root/pending/") })
        assertEquals(listOf("updated V2"), platform.events)
        assertEquals(FlowIntent.NONE, store.record.intent)
        assertEquals(listOf("V2", "V1"), engine.backups.value.map { it.version })
        assertNull(engine.status.value.error)
    }

    @Test
    fun `reboot into the same slot reports that the update did not apply`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        assertTrue(engine.patch().ok)
        device.write(partA, "ksu:stock:V1")
        device.engine("IDLE", 0)
        platform.boot = "boot-2"
        engine.refresh()
        assertEquals(Phase.IDLE, engine.status.value.phase)
        assertEquals("UPDATE_NOT_APPLIED", engine.status.value.error)
        assertEquals(FlowIntent.NONE, store.record.intent)
        assertTrue(platform.events.isEmpty())
    }

    @Test
    fun `lost root after an unplanned reboot while prepared shows the saved image`() = runBlocking {
        assertTrue(engine.prepare().ok)
        platform.boot = "boot-2"
        device.shell.root = false
        engine.refresh()
        assertEquals(Phase.NO_ROOT, engine.status.value.phase)
        assertEquals("ROOT_LOST $root/V1/init_boot_ksu.img", engine.status.value.error)
    }

    @Test
    fun `stock current slot found without a record is adopted as prepared`() = runBlocking {
        device.write(partA, "stock:V1")
        engine.refresh()
        assertEquals(Phase.PREPARED, engine.status.value.phase)
        assertEquals(FlowIntent.PREPARED, store.record.intent)
        assertEquals(sha1Of("stock:V1"), store.record.stockSha1)
    }

    @Test
    fun `concurrent actions are serialized and re-checked`() = runBlocking {
        val first = async { engine.prepare() }
        val second = async { engine.prepare() }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it.ok })
        assertEquals(listOf("WRONG_PHASE"), results.filterNot { it.ok }.map { it.message })
        assertEquals(1, device.shell.count("ksud boot-restore"))
        assertEquals(1, device.shell.maxInFlight)
        assertFalse(engine.busy.value)
    }

    @Test
    fun `install progress from the watcher updates the status`() = runBlocking {
        assertTrue(engine.prepare().ok)
        engine.onEngineUpdate(EngineUpdate(EngineStatus.VERIFYING, 0.5f))
        assertEquals(Phase.INSTALLING, engine.status.value.phase)
        assertEquals(0.875f, engine.status.value.progress!!, 0.0001f)
    }

    @Test
    fun `watcher report of an installed update triggers the automatic patch`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        engine.onEngineUpdate(EngineUpdate(EngineStatus.UPDATED_NEED_REBOOT, 1f))
        waitFor { engine.status.value.phase == Phase.PATCHED && !engine.busy.value }
        waitFor { platform.events.isNotEmpty() }
        assertEquals(listOf("patched"), platform.events)
        assertEquals("ksu:stock:V2", device.files[partB])
    }

    @Test
    fun `automatic patch is skipped when disabled`() = runBlocking {
        engine.setAutoPatch(false)
        assertFalse(store.settings.autoPatch)
        assertTrue(engine.prepare().ok)
        installUpdate()
        engine.onEngineUpdate(EngineUpdate(EngineStatus.UPDATED_NEED_REBOOT, 1f))
        waitFor { engine.status.value.phase == Phase.INSTALLED }
        delay(SETTLE_MS)
        assertEquals(0, device.shell.count("ksud boot-patch"))
        assertEquals(false, platform.watcher.last())
    }

    @Test
    fun `failed automatic patch is reported once and not retried`() = runBlocking {
        assertTrue(engine.prepare().ok)
        installUpdate()
        device.patchWrites = "garbage"
        engine.onEngineUpdate(EngineUpdate(EngineStatus.UPDATED_NEED_REBOOT, 1f))
        waitFor { platform.events.isNotEmpty() }
        assertEquals(listOf("autopatch failed NEW_SLOT_FAILED $root/pending"), platform.events)
        engine.onEngineUpdate(EngineUpdate(EngineStatus.UPDATED_NEED_REBOOT, 1f))
        delay(SETTLE_MS)
        assertEquals(1, device.shell.count("ksud boot-patch"))
    }

    @Test
    fun `old backups are pruned to the latest three builds`() = runBlocking {
        listOf("V-2", "V-1", "V0").forEach { version ->
            device.write("$root/$version/init_boot_stock.img", "stock:$version")
            device.write("$root/$version/init_boot_ksu.img", "ksu:stock:$version")
        }
        assertTrue(engine.prepare().ok)
        assertEquals(listOf("V1", "V0", "V-1"), engine.backups.value.map { it.version })
        assertTrue(device.files.keys.none { it.startsWith("$root/V-2/") })
    }

    @Test
    fun `flash steps are logged`() = runBlocking {
        assertTrue(engine.prepare().ok)
        assertTrue(log.lines.any { it.startsWith("flash: ") && it.contains("boot-restore -f") })
        assertTrue(log.lines.any { it.startsWith("restore verify:") && it.endsWith("OK") })
        assertTrue(engine.readLog().isNotEmpty())
    }

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

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 200L
    }
}
