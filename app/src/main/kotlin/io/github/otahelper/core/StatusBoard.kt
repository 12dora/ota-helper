package io.github.otahelper.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The engine's published state. Every read-modify-write of it happens under one lock, so a watcher
 * status line, a refresh and an action can't interleave and leave stale state behind.
 *
 * Errors shown in [OtaStatus.error], by priority:
 * - [failure]: a flash did not verify. It forces [Phase.FAILED] and outlives later readings until an
 *   explicit refresh sees an explainable device state (the "retry" of the FAILED screen).
 * - [notice]: the last failed action (or a one-time event). It is dropped as soon as the phase moves on,
 *   so it never hides a newer problem; for NO_ROOT/UNSUPPORTED the device problem always wins.
 * - the derivation's own problem.
 */
class StatusBoard(private val platform: Platform, private val log: EventLog, private val settings: () -> Settings) {
    private val lock = Any()
    private val statusFlow = MutableStateFlow(OtaStatus(Phase.LOADING))
    private val backupsFlow = MutableStateFlow<List<Backup>>(emptyList())

    private var lastReading: Reading? = null
    private var notice: Note? = null
    private var noticePhase: Phase? = null
    private var failure: Note? = null

    /** Boot in which auto patch failed; it is not retried in the same boot. */
    private var autoPatchFailedBoot: String? = null

    /** Last watcher state the platform accepted; null when unknown or the last start was refused. */
    private var watcherRunning: Boolean? = null

    val status: StateFlow<OtaStatus> = statusFlow.asStateFlow()
    val backups: StateFlow<List<Backup>> = backupsFlow.asStateFlow()

    /** True after a flash failed verification: no action may run until a refresh clears it. */
    val blocked: Boolean get() = synchronized(lock) { failure != null }

    val reading: Reading? get() = synchronized(lock) { lastReading }

    /** A full device reading. [explicit] (a user/boot refresh) may clear a [failure] the device no longer shows. */
    fun publish(reading: Reading, explicit: Boolean = false) {
        synchronized(lock) {
            if (explicit && failure != null && reading.derivation.phase != Phase.FAILED) {
                log.log("failure cleared: device reads ${reading.derivation.phase}")
                failure = null
            }
            reading.notice?.let { setNotice(it, reading.derivation.phase) }
            reading.backups?.let { backupsFlow.value = it }
            render(reading, ensureWatcher = true)
        }
    }

    /** Start of an action: its result replaces any earlier notice. */
    fun actionStarted() {
        synchronized(lock) {
            notice = null
            noticePhase = null
        }
    }

    /** End of an action; [after] is the reading taken afterwards (null after a reboot command). */
    fun actionFinished(ok: Boolean, note: Note, after: Reading?) {
        synchronized(lock) {
            if (!ok && note.msg in FAILURES) {
                log.log("failure: ${note.msg} ${note.arg.orEmpty()}")
                failure = note
            }
            val phase = after?.derivation?.phase ?: lastReading?.derivation?.phase
            if (!ok) setNotice(note, phase)
            if (after != null) publish(after) else lastReading?.let { render(it, ensureWatcher = true) }
        }
    }

    fun autoPatchFailed(bootId: String) {
        synchronized(lock) {
            autoPatchFailedBoot = bootId
            lastReading?.let { render(it, ensureWatcher = true) }
        }
    }

    fun canAutoPatch(reading: Reading): Boolean = synchronized(lock) {
        failure == null && autoPatchFailedBoot != reading.bootId &&
            reading.derivation.phase == Phase.INSTALLED && settings().autoPatch
    }

    /** Settings changed: re-evaluate the watcher. */
    fun settingsChanged() {
        synchronized(lock) {
            lastReading?.let { render(it, ensureWatcher = true) }
        }
    }

    /**
     * A status line from the watcher. Ignored while a refresh or action holds the engine lock
     * ([engineBusy]): that one publishes a newer full reading anyway. Only install progress is applied
     * here; anything else needs a full reading.
     */
    fun engineUpdate(update: EngineUpdate, record: FlowRecord, engineBusy: () -> Boolean) {
        synchronized(lock) {
            val reading = lastReading ?: return
            val snapshot = reading.snapshot ?: return
            val current = snapshot.current.state == BootImageState.STOCK
            if (!current || engineBusy() || update == snapshot.engine) return
            // The last inactive-slot reading only carries over for postinstall after our patch
            // (PATCHED -> FINALIZING); any other status needs a fresh reading of that slot.
            val keepInactive = reading.derivation.phase == Phase.PATCHED && update.status == EngineStatus.FINALIZING
            val next = snapshot.copy(engine = update, inactive = snapshot.inactive.takeIf { keepInactive })
            val derivation = PhaseRules.derive(next, record, settings().backupDir)
            render(reading.copy(snapshot = next, derivation = derivation, notice = null), ensureWatcher = false)
        }
    }

    private fun setNotice(note: Note, phase: Phase?) {
        notice = note
        noticePhase = phase
    }

    private fun render(reading: Reading, ensureWatcher: Boolean) {
        lastReading = reading
        val derivation = reading.derivation
        if (notice != null && noticePhase != derivation.phase) {
            notice = null
            noticePhase = null
        }
        val phase = if (failure != null) Phase.FAILED else derivation.phase
        val problem = when (phase) {
            Phase.FAILED -> failure ?: derivation.problem
            Phase.NO_ROOT, Phase.UNSUPPORTED -> derivation.problem ?: notice
            else -> notice ?: derivation.problem
        }
        val probe = reading.probe
        val snapshot = reading.snapshot
        statusFlow.value = OtaStatus(
            phase = phase,
            version = probe?.version.orEmpty(),
            slot = probe?.slotLetter.orEmpty(),
            partition = probe?.partition.orEmpty(),
            bootImage = snapshot?.current?.state ?: BootImageState.UNKNOWN,
            engine = snapshot?.engine?.status ?: EngineStatus.UNKNOWN,
            progress = derivation.progress.takeIf { phase == Phase.INSTALLING },
            rootManager = probe?.rootManager.orEmpty(),
            error = problem?.let(platform::text),
        )
        syncWatcher(phase, reading.bootId, ensureWatcher)
    }

    /**
     * Starts the watcher whenever it is needed and [ensure] is set (start is idempotent and may have been
     * refused earlier, or the service may have been stopped by the system); stops it on change only.
     */
    private fun syncWatcher(phase: Phase, bootId: String, ensure: Boolean) {
        val failedHere = phase == Phase.INSTALLED && autoPatchFailedBoot == bootId
        val want = PhaseRules.needsWatcher(phase, settings().autoPatch) && !failedHere
        if (want && (ensure || watcherRunning != true)) {
            val started = platform.setWatcherRunning(true)
            if (watcherRunning != true) log.log("watcher: start ${if (started) "ok" else "refused"}")
            watcherRunning = if (started) true else null
        } else if (!want && watcherRunning != false) {
            log.log("watcher: stop")
            platform.setWatcherRunning(false)
            watcherRunning = false
        }
    }

    private companion object {
        val FAILURES = setOf(Msg.CURRENT_SLOT_FAILED, Msg.NEW_SLOT_FAILED)
    }
}
