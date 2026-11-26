package io.github.otahelper.core

import android.annotation.SuppressLint
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract between the OTA engine (core) and the UI. The UI only reads [status], [settings] and
 * [backups] and calls the suspend actions; it never touches the root shell directly.
 */
interface OtaController {
    val status: StateFlow<OtaStatus>
    val settings: StateFlow<Settings>
    val backups: StateFlow<List<Backup>>

    /** True while an action (prepare/cancel/patch/reboot) is running; actions are serialized. */
    val busy: StateFlow<Boolean>

    /** Re-reads device state (slot, partition hashes, update_engine status) and backups. */
    suspend fun refresh()

    /** Saves the stock image of the running build, then flashes it to the current slot. */
    suspend fun prepare(): ActionResult

    /** Re-patches the current slot after [prepare] when no update was installed. */
    suspend fun cancel(): ActionResult

    /** Backs up the freshly installed stock image of the inactive slot, then patches and flashes it. */
    suspend fun patch(): ActionResult

    suspend fun reboot(): ActionResult

    /**
     * Opens the Xiaomi (HyperOS) system updater through root; its activity is permission-protected, so a
     * plain intent cannot reach it. Returns false when that failed and the UI should fall back to intents.
     */
    suspend fun openSystemUpdate(): Boolean

    suspend fun setAutoPatch(enabled: Boolean)

    /** Most recent log lines, oldest first. */
    suspend fun readLog(): List<String>
}

/** Where the user is in the OTA flow. The UI derives its single primary action from this. */
enum class Phase {
    /** Root check or device detection still running. */
    LOADING,

    /** Root not granted / su unavailable. */
    NO_ROOT,

    /** Not a KernelSU LKM + A/B device; [OtaStatus.error] explains why. */
    UNSUPPORTED,

    /** Current slot patched, no update pending. Next: prepare. */
    IDLE,

    /** Current slot restored to stock; waiting for the user to install the update. Next: install / cancel. */
    PREPARED,

    /** update_engine is applying the update ([OtaStatus.progress]). */
    INSTALLING,

    /** Update installed (needs reboot) but the inactive slot is still stock. Next: patch. */
    INSTALLED,

    /** Inactive slot patched. Next: reboot. */
    PATCHED,

    /**
     * A flash did not verify, or a partition is in a state the flow cannot explain. [OtaStatus.error] says
     * what happened and whether rebooting is unsafe. No primary action besides retry; the log is the way out.
     */
    FAILED,
}

enum class BootImageState { PATCHED, STOCK, UNKNOWN }

/** Mirrors android update_engine's UpdateStatus. */
enum class EngineStatus {
    IDLE,
    CHECKING_FOR_UPDATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    FINALIZING,
    UPDATED_NEED_REBOOT,
    REPORTING_ERROR_EVENT,
    ATTEMPTING_ROLLBACK,
    DISABLED,
    NEED_PERMISSION_TO_UPDATE,
    CLEANUP_PREVIOUS_UPDATE,
    UNKNOWN,
}

data class OtaStatus(
    val phase: Phase,
    /** ro.build.version.incremental, e.g. "OS4.0.5.0.XPACNXM". */
    val version: String = "",
    /** "A" or "B". */
    val slot: String = "",
    /** Partition KernelSU patches, e.g. "init_boot". */
    val partition: String = "",
    val bootImage: BootImageState = BootImageState.UNKNOWN,
    val engine: EngineStatus = EngineStatus.UNKNOWN,
    /** 0f..1f while [Phase.INSTALLING], else null. */
    val progress: Float? = null,
    /** e.g. "KernelSU 3.3.1". */
    val rootManager: String = "",
    /** Short, user-facing; null when there is nothing wrong. */
    val error: String? = null,
)

data class Settings(val autoPatch: Boolean = true, val backupDir: String = DEFAULT_BACKUP_DIR) {
    companion object {
        // Shown to the user and written through the root shell, which resolves /sdcard itself.
        @SuppressLint("SdCardPath")
        const val DEFAULT_BACKUP_DIR = "/sdcard/Download/advanced/init_boot"
    }
}

/** One build's saved images. Paths are absolute device paths; either may be absent. */
data class Backup(val version: String, val stockPath: String?, val patchedPath: String?, val modifiedMillis: Long)

data class ActionResult(val ok: Boolean, val message: String)
