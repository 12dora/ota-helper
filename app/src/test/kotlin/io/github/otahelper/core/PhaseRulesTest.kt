package io.github.otahelper.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseRulesTest {
    private val probe = Probe(
        abUpdate = true,
        slotSuffix = "_a",
        version = "OS4.0.5.0.XPACNXM",
        ksudVersion = "3.3.1",
        lkm = true,
        partition = "init_boot",
        magiskboot = true,
        devices = setOf("init_boot_a", "init_boot_b", "boot_a", "boot_b"),
    )
    private val patched = ImageInfo(BootImageState.PATCHED, "a".repeat(40), 1, "b".repeat(40))
    private val stock = ImageInfo(BootImageState.STOCK, "c".repeat(40), 1, null)
    private val unknown = ImageInfo(BootImageState.UNKNOWN, null, null, null)

    private fun derive(
        current: ImageInfo,
        engine: EngineStatus? = null,
        inactive: ImageInfo? = null,
        progress: Float = 0f,
        intent: FlowIntent = FlowIntent.NONE,
    ) = PhaseRules.derive(Snapshot(probe, current, engine?.let { EngineUpdate(it, progress) }, inactive), intent)

    @Test
    fun `patched current slot is idle whatever the intent`() {
        FlowIntent.entries.forEach { intent ->
            val result = derive(patched, intent = intent)
            assertEquals(Phase.IDLE, result.phase)
            assertEquals(FlowIntent.NONE, result.intent)
            assertNull(result.problem)
        }
    }

    @Test
    fun `stock current slot without an update is prepared`() {
        listOf(
            EngineStatus.IDLE,
            EngineStatus.CHECKING_FOR_UPDATE,
            EngineStatus.UPDATE_AVAILABLE,
            EngineStatus.NEED_PERMISSION_TO_UPDATE,
            EngineStatus.DISABLED,
        ).forEach { engine ->
            val result = derive(stock, engine)
            assertEquals(Phase.PREPARED, result.phase, engine.name)
            assertEquals(FlowIntent.PREPARED, result.intent)
            assertNull(result.problem)
        }
    }

    @Test
    fun `install stages are installing with overall progress`() {
        val result = derive(stock, EngineStatus.DOWNLOADING, progress = 0.5f)
        assertEquals(Phase.INSTALLING, result.phase)
        assertEquals(0.425f, result.progress!!, 0.0001f)
        assertEquals(Phase.INSTALLING, derive(stock, EngineStatus.VERIFYING).phase)
        assertEquals(Phase.INSTALLING, derive(stock, EngineStatus.FINALIZING).phase)
        assertEquals(Phase.INSTALLING, derive(stock, EngineStatus.CLEANUP_PREVIOUS_UPDATE).phase)
    }

    @Test
    fun `installed update with stock inactive slot needs a patch`() {
        val result = derive(stock, EngineStatus.UPDATED_NEED_REBOOT, inactive = stock)
        assertEquals(Phase.INSTALLED, result.phase)
        assertEquals(FlowIntent.PREPARED, result.intent)
        assertNull(result.problem)
    }

    @Test
    fun `installed update with patched inactive slot is patched`() {
        val result = derive(stock, EngineStatus.UPDATED_NEED_REBOOT, inactive = patched, intent = FlowIntent.PREPARED)
        assertEquals(Phase.PATCHED, result.phase)
        assertEquals(FlowIntent.PATCHED, result.intent)
    }

    @Test
    fun `unreadable inactive slot stays installed with a problem`() {
        val result = derive(stock, EngineStatus.UPDATED_NEED_REBOOT, inactive = unknown)
        assertEquals(Phase.INSTALLED, result.phase)
        assertEquals(Msg.UNKNOWN_IMAGE, result.problem?.msg)
    }

    @Test
    fun `failed install and unknown engine are reported on prepared`() {
        assertEquals(Msg.INSTALL_FAILED, derive(stock, EngineStatus.REPORTING_ERROR_EVENT).problem?.msg)
        val unknownEngine = derive(stock, null)
        assertEquals(Phase.PREPARED, unknownEngine.phase)
        assertEquals(Msg.ENGINE_UNKNOWN, unknownEngine.problem?.msg)
        assertEquals(Msg.ENGINE_UNKNOWN, derive(stock, EngineStatus.UNKNOWN).problem?.msg)
    }

    @Test
    fun `unknown current image is unsupported and keeps the intent`() {
        val result = derive(unknown, intent = FlowIntent.PREPARED)
        assertEquals(Phase.UNSUPPORTED, result.phase)
        assertEquals(FlowIntent.PREPARED, result.intent)
        assertEquals(Msg.UNKNOWN_IMAGE, result.problem?.msg)
    }

    @Test
    fun `unsupported devices`() {
        assertEquals(Msg.NOT_AB, probe.copy(abUpdate = false).unsupportedReason()?.msg)
        assertEquals(Msg.NOT_AB, probe.copy(slotSuffix = "").unsupportedReason()?.msg)
        assertEquals(Msg.NO_KERNELSU, probe.copy(ksudVersion = null).unsupportedReason()?.msg)
        assertEquals(Msg.NOT_LKM, probe.copy(lkm = false).unsupportedReason()?.msg)
        assertEquals(Msg.NOT_LKM, probe.copy(lkm = null).unsupportedReason()?.msg)
        assertEquals(
            Note(Msg.UNSUPPORTED_PARTITION, "vendor_boot"),
            probe.copy(partition = "vendor_boot").unsupportedReason(),
        )
        assertEquals(Msg.UNSUPPORTED_PARTITION, probe.copy(devices = setOf("init_boot_a")).unsupportedReason()?.msg)
        assertEquals(Msg.TOOLS_MISSING, probe.copy(magiskboot = false).unsupportedReason()?.msg)
        assertNull(probe.unsupportedReason())
        assertNull(probe.copy(partition = "boot").unsupportedReason())
        assertEquals(
            Phase.UNSUPPORTED,
            PhaseRules.derive(Snapshot(probe.copy(lkm = false), patched), FlowIntent.NONE).phase,
        )
    }

    @Test
    fun `probe paths and labels`() {
        assertEquals("/dev/block/by-name/init_boot_a", probe.partitionPath(inactive = false))
        assertEquals("/dev/block/by-name/init_boot_b", probe.partitionPath(inactive = true))
        assertEquals("A", probe.slotLetter)
        assertEquals("_a", probe.copy(slotSuffix = "_b").otherSuffix)
        assertEquals("KernelSU 3.3.1", probe.rootManager)
    }

    @Test
    fun `lost root after an unplanned reboot points at the saved patched image`() {
        val record = FlowRecord(FlowIntent.PREPARED, "_a", "boot-1", "V1", "init_boot", "c".repeat(40))
        assertEquals(
            Note(Msg.ROOT_LOST, "/sdcard/x/V1/init_boot_ksu.img"),
            PhaseRules.noRootProblem(record, "boot-2", "/sdcard/x"),
        )
        assertEquals(Note(Msg.NO_ROOT), PhaseRules.noRootProblem(record, "boot-1", "/sdcard/x"))
        assertEquals(Note(Msg.NO_ROOT), PhaseRules.noRootProblem(FlowRecord(), "boot-2", "/sdcard/x"))
    }

    @Test
    fun `watcher runs while waiting for or installing the update`() {
        assertTrue(PhaseRules.needsWatcher(Phase.PREPARED, autoPatch = false))
        assertTrue(PhaseRules.needsWatcher(Phase.INSTALLING, autoPatch = false))
        assertTrue(PhaseRules.needsWatcher(Phase.INSTALLED, autoPatch = true))
        assertFalse(PhaseRules.needsWatcher(Phase.INSTALLED, autoPatch = false))
        listOf(Phase.LOADING, Phase.NO_ROOT, Phase.UNSUPPORTED, Phase.IDLE, Phase.PATCHED).forEach {
            assertFalse(PhaseRules.needsWatcher(it, autoPatch = true))
        }
    }

    @Test
    fun `backup paths are validated`() {
        assertTrue(BackupStore.isValidRoot(Settings.DEFAULT_BACKUP_DIR))
        assertTrue(BackupStore.isValidRoot("/storage/emulated/0/Download/OTA backups"))
        assertFalse(BackupStore.isValidRoot("/sdcard"))
        assertFalse(BackupStore.isValidRoot("/data/adb/ksu"))
        assertFalse(BackupStore.isValidRoot("/sdcard/../data"))
        assertFalse(BackupStore.isValidRoot("/sdcard/a'b"))
        assertFalse(BackupStore.isValidRoot("/sdcard/x/"))
        assertEquals("OS4.0.5.0.XPACNXM", BackupStore.safeVersion("OS4.0.5.0.XPACNXM"))
        assertEquals("a_b_c", BackupStore.safeVersion("a/b c"))
        assertEquals("unknown", BackupStore.safeVersion(".."))
        assertFalse(BackupStore.isSafeName("pending/x"))
        assertFalse(BackupStore.isSafeName(".."))
    }
}
