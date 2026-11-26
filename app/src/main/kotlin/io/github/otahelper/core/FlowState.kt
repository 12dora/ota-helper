package io.github.otahelper.core

/** What the app last did to the device. Device state always wins; this only fills in what the device can't tell. */
enum class FlowIntent {
    NONE,

    /** The current slot was restored to stock by [OtaController.prepare]. */
    PREPARED,

    /** The inactive slot was patched by [OtaController.patch]; a reboot into it is expected. */
    PATCHED,
}

/** Persisted flow record, written before and after every flash. */
data class FlowRecord(
    val intent: FlowIntent = FlowIntent.NONE,
    /** Slot suffix ("_a"/"_b") that was running when the record was written. */
    val slot: String = "",
    /** Boot counter when the record was written; a different value means the device rebooted since. */
    val bootId: String = "",
    /** Build that was running when the record was written. */
    val version: String = "",
    val partition: String = "",
    /** SHA-1 of the running build's stock image. */
    val stockSha1: String = "",
)

/** Small persistent store for the flow record and settings. Writes are synchronous and durable. */
interface StateStore {
    fun record(): FlowRecord

    fun saveRecord(record: FlowRecord)

    fun settings(): Settings

    fun saveSettings(settings: Settings)
}
