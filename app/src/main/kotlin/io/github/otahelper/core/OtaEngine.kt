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
 * NonCancellable: once a flow has started it finishes its checks even if the caller goes away.
 */
class OtaEngine(
    shell: Shell,
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
    private val statusFlow = MutableStateFlow(OtaStatus(Phase.LOADING))
    private val settingsFlow = MutableStateFlow(store.settings())
    private val backupsFlow = MutableStateFlow<List<Backup>>(emptyList())
    private val busyFlow = MutableStateFlow(false)
    private val autoPatching = AtomicBoolean(false)

    @Volatile private var lastReading: Reading? = null

    /** Result of the last failed action (or a one-time event); shown until the next action starts. */
    @Volatile private var notice: Note? = null

    /** Boot in which auto patch failed; it is not retried in the same boot. */
    @Volatile private var autoPatchFailedBoot: String? = null

    @Volatile private var watcherRequested: Boolean? = null

    override val status: StateFlow<OtaStatus> = statusFlow.asStateFlow()
    override val settings: StateFlow<Settings> = settingsFlow.asStateFlow()
    override val backups: StateFlow<List<Backup>> = backupsFlow.asStateFlow()
    override val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    override suspend fun refresh() {
        withContext(io + NonCancellable) {
            mutex.withLock { publish(stateReader.read(settingsFlow.value)) }
        }
    }

    override suspend fun prepare(): ActionResult = action("prepare", actions::prepare)

    override suspend fun cancel(): ActionResult = action("cancel", actions::cancel)

    override suspend fun patch(): ActionResult = action("patch", actions::patch)

    override suspend fun reboot(): ActionResult = action("reboot", actions::reboot)

    override suspend fun setAutoPatch(enabled: Boolean) {
        val updated = settingsFlow.value.copy(autoPatch = enabled)
        withContext(io) { store.saveSettings(updated) }
        settingsFlow.value = updated
        log.log("settings: autoPatch=$enabled")
        lastReading?.let(::publish)
    }

    override suspend fun readLog(): List<String> = withContext(io) { log.tail() }

    /**
     * Called by the watcher for every update_engine status line. Install progress is applied directly;
     * UPDATED_NEED_REBOOT triggers a full refresh and, when enabled, the automatic patch.
     */
    fun onEngineUpdate(update: EngineUpdate) {
        val reading = lastReading ?: return
        val snapshot = reading.snapshot ?: return
        if (snapshot.current.state != BootImageState.STOCK || busyFlow.value) return
        if (update.status == EngineStatus.UPDATED_NEED_REBOOT) {
            val known = snapshot.engine?.status == EngineStatus.UPDATED_NEED_REBOOT
            scope.launch { onInstalled(refreshFirst = !known) }
            return
        }
        if (update == snapshot.engine) return
        val next = snapshot.copy(engine = update, inactive = null)
        publish(reading.copy(snapshot = next, derivation = PhaseRules.derive(next, reading.derivation.intent)))
    }

    private suspend fun onInstalled(refreshFirst: Boolean) {
        if (!autoPatching.compareAndSet(false, true)) return
        try {
            if (refreshFirst) refresh()
            val bootId = lastReading?.bootId
            val eligible = statusFlow.value.phase == Phase.INSTALLED && settingsFlow.value.autoPatch
            if (eligible && autoPatchFailedBoot != bootId) {
                log.log("auto patch: update installed, patching new slot")
                val result = platform.keepAwake { patch() }
                if (result.ok) {
                    platform.notifyPatched()
                } else {
                    autoPatchFailedBoot = bootId
                    platform.notifyAutoPatchFailed(result.message)
                    lastReading?.let(::publish)
                }
            }
        } finally {
            autoPatching.set(false)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Boundary: any failure becomes a reported, logged error.
    private suspend fun action(name: String, block: suspend (ActionContext) -> Note): ActionResult =
        withContext(io + NonCancellable) {
            mutex.withLock {
                busyFlow.value = true
                notice = null
                try {
                    log.log("action: $name")
                    val reading = stateReader.read(settingsFlow.value)
                    publish(reading)
                    val (ok, note) = try {
                        true to block(ActionContext(reading, settingsFlow.value))
                    } catch (e: ActionFailure) {
                        log.log("action $name stopped: ${e.message}")
                        false to e.note
                    } catch (e: Exception) {
                        log.log("action $name crashed: $e")
                        false to Note(Msg.INTERNAL)
                    }
                    log.log("action $name: ${if (ok) "done" else "failed"} (${note.msg})")
                    if (!ok) notice = note
                    if (!ok || name != "reboot") publish(stateReader.read(settingsFlow.value))
                    ActionResult(ok, platform.text(note))
                } finally {
                    busyFlow.value = false
                }
            }
        }

    private fun publish(reading: Reading) {
        lastReading = reading
        reading.notice?.let { notice = it }
        reading.backups?.let { backupsFlow.value = it }
        val derivation = reading.derivation
        val probe = reading.probe
        val snapshot = reading.snapshot
        statusFlow.value = OtaStatus(
            phase = derivation.phase,
            version = probe?.version.orEmpty(),
            slot = probe?.slotLetter.orEmpty(),
            partition = probe?.partition.orEmpty(),
            bootImage = snapshot?.current?.state ?: BootImageState.UNKNOWN,
            engine = snapshot?.engine?.status ?: EngineStatus.UNKNOWN,
            progress = derivation.progress,
            rootManager = probe?.rootManager.orEmpty(),
            error = (notice ?: derivation.problem)?.let(platform::text),
        )
        val watch = PhaseRules.needsWatcher(derivation.phase, settingsFlow.value.autoPatch) &&
            !(derivation.phase == Phase.INSTALLED && autoPatchFailedBoot == reading.bootId)
        if (watch != watcherRequested) {
            watcherRequested = watch
            log.log("watcher: ${if (watch) "start" else "stop"}")
            platform.setWatcherRunning(watch)
        }
    }
}
