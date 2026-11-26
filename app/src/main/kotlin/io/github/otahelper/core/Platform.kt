package io.github.otahelper.core

/** Everything the engine needs from Android, so the engine itself stays plain Kotlin. */
interface Platform {
    /** Changes on every boot (Settings.Global.BOOT_COUNT). Readable without root. */
    fun bootId(): String

    /** Running slot suffix ("_a"/"_b") read without root; empty when unknown. */
    fun slotSuffix(): String

    fun text(note: Note): String

    /** Starts or stops the update watcher service. Idempotent. False when the system refused the start. */
    fun setWatcherRunning(running: Boolean): Boolean

    fun notifyPatched()

    fun notifyUpdated(version: String)

    fun notifyAutoPatchFailed(message: String)

    /** Keeps the CPU awake while [block] runs (auto patch may start with the screen off). */
    suspend fun <T> keepAwake(block: suspend () -> T): T
}
