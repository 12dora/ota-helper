package io.github.otahelper.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [OtaController] over a root [Shell]. One mutex serializes every refresh and action, and actions run
 * NonCancellable: once a flow has started it finishes its checks even if the caller goes away. Published
 * state lives in [StatusBoard], which serializes its own updates.
 */
class OtaEngine(
    private val shell: Shell,
    private val store: StateStore,
    private val log: EventLog,
    private val platform: Platform,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : OtaController {
    /** Long-lived scope for work that outlives a screen: auto patch and boot handling. */
    val scope = CoroutineScope(SupervisorJob() + io)

    private val reader = DeviceReader(shell, log)
    private val backupStore = BackupStore(shell, reader, log)
    private val stateReader = StateReader(reader, backupStore, store, platform, log)
    private val actions = OtaActions(reader, Flasher(shell, reader, log), backupStore, store, log)

    private val mutex = Mutex()
    private val settingsFlow = MutableStateFlow(store.settings())
    private val board = StatusBoard(platform, log) { settingsFlow.value }
    private val busyFlow = MutableStateFlow(false)
    private val autoPatching = AtomicBoolean(false)

    override val status: StateFlow<OtaStatus> = board.status
    override val settings: StateFlow<Settings> = settingsFlow.asStateFlow()
    override val backups: StateFlow<List<Backup>> = board.backups
    override val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    override suspend fun refresh() {
        withContext(io + NonCancellable) {
            mutex.withLock { board.publish(stateReader.read(settingsFlow.value), explicit = true) }
        }
    }

    override suspend fun prepare(): ActionResult = action("prepare", block = actions::prepare)

    override suspend fun cancel(): ActionResult = action("cancel", block = actions::cancel)

    override suspend fun patch(): ActionResult = action("patch", block = actions::patch)

    override suspend fun reboot(): ActionResult = action("reboot", block = actions::reboot)

    // Read-only for device state, so it does not take the action mutex.
    override suspend fun openSystemUpdate(): Boolean = withContext(io) {
        val result = shell.run("am start -n $UPDATER_ACTIVITY 2>&1", UPDATER_TIMEOUT_MS)
        val started = result.ok && result.stdout.lineSequence().none { it.startsWith("Error") || "Exception" in it }
        log.log("open updater: ${if (started) "ok" else "failed (${result.code})"}")
        started
    }

    override suspend fun setAutoPatch(enabled: Boolean) {
        val updated = settingsFlow.value.copy(autoPatch = enabled)
        withContext(io) { store.saveSettings(updated) }
        settingsFlow.value = updated
        log.log("settings: autoPatch=$enabled")
        board.settingsChanged()
    }

    override suspend fun readLog(): List<String> = withContext(io) { log.tail() }

    /**
     * Called by the watcher for every update_engine status line. Install progress is applied directly
     * (unless a refresh or action is running); UPDATED_NEED_REBOOT starts the automatic patch, which
     * re-reads the device and decides under the engine lock.
     */
    fun onEngineUpdate(update: EngineUpdate) {
        if (update.status == EngineStatus.UPDATED_NEED_REBOOT) {
            if (board.reading?.snapshot?.current?.state == BootImageState.STOCK) scope.launch { autoPatch() }
            return
        }
        board.engineUpdate(update, store.record()) { mutex.isLocked }
    }

    private suspend fun autoPatch() {
        if (!autoPatching.compareAndSet(false, true)) return
        try {
            val result = platform.keepAwake {
                action("auto patch", gate = board::canAutoPatch, block = actions::patch)
            }
            when {
                result === SKIPPED -> Unit

                result.ok -> platform.notifyPatched()

                else -> {
                    board.reading?.bootId?.let(board::autoPatchFailed)
                    platform.notifyAutoPatchFailed(result.message)
                }
            }
        } finally {
            autoPatching.set(false)
        }
    }

    /**
     * Runs one action under the engine lock: reads the device, checks [gate] (skipped silently when it
     * fails), runs [block], then reads the device again. Refused while a verification failure stands.
     */
    @Suppress("TooGenericExceptionCaught") // Boundary: any failure becomes a reported, logged error.
    private suspend fun action(
        name: String,
        gate: (Reading) -> Boolean = { true },
        block: suspend (ActionContext) -> Note,
    ): ActionResult = withContext(io + NonCancellable) {
        mutex.withLock {
            busyFlow.value = true
            try {
                val reading = stateReader.read(settingsFlow.value)
                board.publish(reading)
                if (!gate(reading)) return@withLock SKIPPED
                log.log("action: $name")
                board.actionStarted()
                val (ok, note) = if (board.blocked) {
                    false to Note(Msg.WRONG_PHASE)
                } else {
                    try {
                        true to block(ActionContext(reading, settingsFlow.value))
                    } catch (e: ActionFailure) {
                        log.log("action $name stopped: ${e.message}")
                        false to e.note
                    } catch (e: Exception) {
                        log.log("action $name crashed: $e")
                        false to Note(Msg.INTERNAL)
                    }
                }
                log.log("action $name: ${if (ok) "done" else "failed"} (${note.msg})")
                val after = if (!ok || name != "reboot") stateReader.read(settingsFlow.value) else null
                board.actionFinished(ok, note, after)
                ActionResult(ok, platform.text(note))
            } finally {
                busyFlow.value = false
            }
        }
    }

    private companion object {
        const val UPDATER_ACTIVITY = "com.android.updater/.MainActivity"
        const val UPDATER_TIMEOUT_MS = 5_000L

        /** Returned by [action] when its gate did not pass; compared by identity. */
        val SKIPPED = ActionResult(false, "")
    }
}
