package io.github.otahelper.system

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings.Global
import io.github.otahelper.R
import io.github.otahelper.core.EventLog
import io.github.otahelper.core.Msg
import io.github.otahelper.core.Note
import io.github.otahelper.core.Platform
import java.io.File
import java.io.IOException

/** [Platform] backed by the application context. */
class AndroidPlatform(private val context: Context, private val log: EventLog) : Platform {

    override fun bootId(): String {
        val count = Global.getInt(context.contentResolver, Global.BOOT_COUNT, -1)
        if (count >= 0) return "count:$count"
        return try {
            "id:" + File(BOOT_ID_FILE).readText().trim()
        } catch (e: IOException) {
            log.log("boot id unavailable: ${e.message}")
            ""
        }
    }

    override fun slotSuffix(): String = try {
        val process = ProcessBuilder("getprop", "ro.boot.slot_suffix").start()
        process.inputStream.bufferedReader().use { it.readText().trim() }.also { process.waitFor() }
    } catch (e: IOException) {
        log.log("slot suffix unavailable: ${e.message}")
        ""
    }

    override fun text(note: Note): String {
        val id = MESSAGES.getValue(note.msg)
        val arg = note.arg
        return if (arg == null) context.getString(id) else context.getString(id, arg)
    }

    override fun setWatcherRunning(running: Boolean): Boolean {
        val intent = Intent(context, WatcherService::class.java)
        return try {
            if (running) context.startForegroundService(intent) else context.stopService(intent)
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: started from the background. The engine retries
            // on the next refresh or action.
            log.log("watcher: cannot start now: ${e.message}")
            false
        } catch (e: SecurityException) {
            log.log("watcher: not allowed: ${e.message}")
            false
        }
    }

    override fun notifyPatched() = Notifications.postPatched(context)

    override fun notifyUpdated(version: String) = Notifications.postUpdated(context, version)

    override fun notifyAutoPatchFailed(message: String) = Notifications.postAutoPatchFailed(context, message)

    override suspend fun <T> keepAwake(block: suspend () -> T): T {
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        try {
            return block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private companion object {
        const val BOOT_ID_FILE = "/proc/sys/kernel/random/boot_id"
        const val WAKE_LOCK_TAG = "otahelper:patch"
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        val MESSAGES: Map<Msg, Int> = mapOf(
            Msg.PREPARE_DONE to R.string.core_msg_prepare_done,
            Msg.CANCEL_DONE to R.string.core_msg_cancel_done,
            Msg.PATCH_DONE to R.string.core_msg_patch_done,
            Msg.REBOOTING to R.string.core_msg_rebooting,
            Msg.NO_ROOT to R.string.core_msg_no_root,
            Msg.ROOT_LOST to R.string.core_msg_root_lost,
            Msg.ROOT_LOST_AFTER_UPDATE to R.string.core_msg_root_lost_after_update,
            Msg.ROOT_LOST_BACKUPS to R.string.core_msg_root_lost_backups,
            Msg.NOT_AB to R.string.core_msg_not_ab,
            Msg.NO_KERNELSU to R.string.core_msg_no_kernelsu,
            Msg.NOT_LKM to R.string.core_msg_not_lkm,
            Msg.UNSUPPORTED_PARTITION to R.string.core_msg_unsupported_partition,
            Msg.TOOLS_MISSING to R.string.core_msg_tools_missing,
            Msg.READ_FAILED to R.string.core_msg_read_failed,
            Msg.UNKNOWN_IMAGE to R.string.core_msg_unknown_image,
            Msg.WRONG_PHASE to R.string.core_msg_wrong_phase,
            Msg.BACKUP_MISSING to R.string.core_msg_backup_missing,
            Msg.BACKUP_INVALID to R.string.core_msg_backup_invalid,
            Msg.SAVE_FAILED to R.string.core_msg_save_failed,
            Msg.BACKUP_DIR_INVALID to R.string.core_msg_backup_dir_invalid,
            Msg.STATE_CHANGED to R.string.core_msg_state_changed,
            Msg.FLASH_FAILED to R.string.core_msg_flash_failed,
            Msg.CURRENT_SLOT_FAILED to R.string.core_msg_current_slot_failed,
            Msg.NEW_SLOT_FAILED to R.string.core_msg_new_slot_failed,
            Msg.ENGINE_UNKNOWN to R.string.core_msg_engine_unknown,
            Msg.UPDATE_RUNNING to R.string.core_msg_update_running,
            Msg.UPDATE_INSTALLED to R.string.core_msg_update_installed,
            Msg.NOT_INSTALLED to R.string.core_msg_not_installed,
            Msg.INSTALL_FAILED to R.string.core_msg_install_failed,
            Msg.UPDATE_NOT_APPLIED to R.string.core_msg_update_not_applied,
            Msg.REBOOT_FAILED to R.string.core_msg_reboot_failed,
            Msg.INTERNAL to R.string.core_msg_internal,
        )
    }
}
