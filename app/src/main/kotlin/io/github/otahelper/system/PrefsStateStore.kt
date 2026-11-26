package io.github.otahelper.system

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.otahelper.core.FlowIntent
import io.github.otahelper.core.FlowRecord
import io.github.otahelper.core.Settings
import io.github.otahelper.core.StateStore

/**
 * [StateStore] on SharedPreferences from device-protected storage, so the boot receiver can read it.
 * Writes use commit(): the record is written right before a flash and must be on disk when it starts.
 */
class PrefsStateStore(private val prefs: SharedPreferences) : StateStore {

    override fun record(): FlowRecord = FlowRecord(
        intent = prefs.getString(KEY_INTENT, null)
            ?.let { name -> FlowIntent.entries.firstOrNull { it.name == name } }
            ?: FlowIntent.NONE,
        slot = prefs.getString(KEY_SLOT, null).orEmpty(),
        bootId = prefs.getString(KEY_BOOT_ID, null).orEmpty(),
        version = prefs.getString(KEY_VERSION, null).orEmpty(),
        partition = prefs.getString(KEY_PARTITION, null).orEmpty(),
        stockSha1 = prefs.getString(KEY_STOCK_SHA1, null).orEmpty(),
        patchedSha1 = prefs.getString(KEY_PATCHED_SHA1, null).orEmpty(),
    )

    override fun saveRecord(record: FlowRecord) {
        prefs.edit(commit = true) {
            putString(KEY_INTENT, record.intent.name)
            putString(KEY_SLOT, record.slot)
            putString(KEY_BOOT_ID, record.bootId)
            putString(KEY_VERSION, record.version)
            putString(KEY_PARTITION, record.partition)
            putString(KEY_STOCK_SHA1, record.stockSha1)
            putString(KEY_PATCHED_SHA1, record.patchedSha1)
        }
    }

    override fun settings(): Settings = Settings(
        autoPatch = prefs.getBoolean(KEY_AUTO_PATCH, true),
        backupDir = prefs.getString(KEY_BACKUP_DIR, null) ?: Settings.DEFAULT_BACKUP_DIR,
    )

    override fun saveSettings(settings: Settings) {
        prefs.edit(commit = true) {
            putBoolean(KEY_AUTO_PATCH, settings.autoPatch)
            putString(KEY_BACKUP_DIR, settings.backupDir)
        }
    }

    companion object {
        const val FILE_NAME = "ota_state"
        private const val KEY_INTENT = "intent"
        private const val KEY_SLOT = "slot"
        private const val KEY_BOOT_ID = "boot_id"
        private const val KEY_VERSION = "version"
        private const val KEY_PARTITION = "partition"
        private const val KEY_STOCK_SHA1 = "stock_sha1"
        private const val KEY_PATCHED_SHA1 = "patched_sha1"
        private const val KEY_AUTO_PATCH = "auto_patch"
        private const val KEY_BACKUP_DIR = "backup_dir"
    }
}
