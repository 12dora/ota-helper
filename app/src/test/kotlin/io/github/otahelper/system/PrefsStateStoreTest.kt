package io.github.otahelper.system

import android.content.SharedPreferences
import io.github.otahelper.core.FlowIntent
import io.github.otahelper.core.FlowRecord
import io.github.otahelper.core.Settings
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PrefsStateStoreTest {
    @Test
    fun `v1_0_0 prefs without the patched hash read as an empty hash`() {
        val prefs = MemoryPrefs(
            mutableMapOf(
                "intent" to "PATCHED",
                "slot" to "_a",
                "boot_id" to "count:96",
                "version" to "OS4.0.5.0.XPACNXM",
                "partition" to "init_boot",
                "stock_sha1" to "a".repeat(40),
            ),
        )
        val record = PrefsStateStore(prefs).record()
        assertEquals(
            FlowRecord(FlowIntent.PATCHED, "_a", "count:96", "OS4.0.5.0.XPACNXM", "init_boot", "a".repeat(40), ""),
            record,
        )
    }

    @Test
    fun `records and settings round-trip`() {
        val store = PrefsStateStore(MemoryPrefs())
        assertEquals(FlowRecord(), store.record())
        assertEquals(Settings(), store.settings())
        val record = FlowRecord(FlowIntent.PATCHED, "_b", "count:97", "V2", "init_boot", "a".repeat(40), "b".repeat(40))
        store.saveRecord(record)
        assertEquals(record, store.record())
        store.saveRecord(FlowRecord())
        assertEquals(FlowRecord(), store.record())
        val settings = Settings(autoPatch = false, backupDir = "/sdcard/Download/x")
        store.saveSettings(settings)
        assertEquals(settings, store.settings())
    }

    @Test
    fun `an unknown intent name reads as none`() {
        assertEquals(FlowIntent.NONE, PrefsStateStore(MemoryPrefs(mutableMapOf("intent" to "LATER"))).record().intent)
    }
}

/** In-memory [SharedPreferences]; edits apply on commit/apply. */
private class MemoryPrefs(private val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply { pending[key] = value }
    }
}
