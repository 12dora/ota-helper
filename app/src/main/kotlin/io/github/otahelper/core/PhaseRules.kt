package io.github.otahelper.core

/**
 * Device state read in one refresh. [engine] is only queried when the current slot is stock and
 * [inactive] only when update_engine reports UPDATED_NEED_REBOOT; null means "not read".
 */
data class Snapshot(
    val probe: Probe,
    val current: ImageInfo,
    val engine: EngineUpdate? = null,
    val inactive: ImageInfo? = null,
)

/** The phase the device is in, plus the flow intent that matches it. */
data class Derivation(val phase: Phase, val intent: FlowIntent, val progress: Float? = null, val problem: Note? = null)

/**
 * Phase derivation. The phase comes from device state, so it heals itself after the app was killed or the
 * device rebooted mid-flow. The persisted record only decides whether an unreadable image is a failed
 * flow ([Phase.FAILED]) or an unsupported setup, where the saved images are, and how to explain lost root.
 *
 * | current slot | update_engine        | inactive slot | phase                                 |
 * |--------------|----------------------|---------------|---------------------------------------|
 * | patched      | (not read)           | (not read)    | IDLE                                  |
 * | stock        | applying/verifying/… | (not read)    | INSTALLING                            |
 * | stock        | UPDATED_NEED_REBOOT  | stock         | INSTALLED                             |
 * | stock        | UPDATED_NEED_REBOOT  | patched       | PATCHED                               |
 * | stock        | UPDATED_NEED_REBOOT  | unknown       | FAILED                                |
 * | stock        | anything else        | (not read)    | PREPARED                              |
 * | unknown      | –                    | –             | FAILED in a flow, else UNSUPPORTED    |
 */
object PhaseRules {

    fun derive(snapshot: Snapshot, record: FlowRecord, backupDir: String): Derivation {
        val intent = record.intent
        snapshot.probe.unsupportedReason()?.let { return Derivation(Phase.UNSUPPORTED, intent, problem = it) }
        return when (snapshot.current.state) {
            BootImageState.PATCHED -> Derivation(Phase.IDLE, FlowIntent.NONE)

            BootImageState.STOCK -> deriveStock(snapshot, record, backupDir)

            BootImageState.UNKNOWN -> if (intent == FlowIntent.NONE) {
                Derivation(Phase.UNSUPPORTED, intent, problem = Note(Msg.UNKNOWN_IMAGE))
            } else {
                Derivation(Phase.FAILED, intent, problem = currentSlotFailed(record, backupDir))
            }
        }
    }

    /** The current slot did not verify: point at the running build's saved images. */
    fun currentSlotFailed(record: FlowRecord, backupDir: String): Note {
        val dir = if (record.version.isEmpty()) backupDir else BackupStore.versionDir(backupDir, record.version)
        return Note(Msg.CURRENT_SLOT_FAILED, dir)
    }

    /** The inactive slot did not verify: point at the new build's saved stock image. */
    fun newSlotFailed(backupDir: String) = Note(Msg.NEW_SLOT_FAILED, BackupStore.pendingDir(backupDir))

    private fun deriveStock(snapshot: Snapshot, record: FlowRecord, backupDir: String): Derivation {
        val engine = snapshot.engine
        return when {
            engine == null || engine.status == EngineStatus.UNKNOWN ->
                Derivation(Phase.PREPARED, FlowIntent.PREPARED, problem = Note(Msg.ENGINE_UNKNOWN))

            stillOurPatch(snapshot, record) -> Derivation(Phase.PATCHED, FlowIntent.PATCHED)

            engine.status == EngineStatus.UPDATED_NEED_REBOOT -> deriveInstalled(snapshot.inactive, backupDir)

            EngineStatusParser.isInstalling(engine.status) ->
                Derivation(Phase.INSTALLING, FlowIntent.PREPARED, EngineStatusParser.overallProgress(engine))

            engine.status == EngineStatus.REPORTING_ERROR_EVENT ->
                Derivation(Phase.PREPARED, FlowIntent.PREPARED, problem = Note(Msg.INSTALL_FAILED))

            else -> Derivation(Phase.PREPARED, FlowIntent.PREPARED)
        }
    }

    /**
     * After our patch, update_engine may report FINALIZING again (HyperOS runs an async optional
     * postinstall for about a minute, then returns to UPDATED_NEED_REBOOT). Postinstall never writes
     * init_boot, so the flow stays PATCHED as long as the inactive slot still holds exactly the image we
     * flashed. Any other running stage (CLEANUP_PREVIOUS_UPDATE, DOWNLOADING, VERIFYING) means a new
     * payload is being applied, and a patched inactive slot with any other hash proves nothing.
     */
    fun stillOurPatch(snapshot: Snapshot, record: FlowRecord): Boolean {
        val inactive = snapshot.inactive
        val status = snapshot.engine?.status ?: return false
        return listOf(
            record.intent == FlowIntent.PATCHED,
            record.patchedSha1.isNotEmpty(),
            inactive?.state == BootImageState.PATCHED,
            inactive?.sha1 == record.patchedSha1,
            keepsOurPatch(status),
        ).all { it }
    }

    /** update_engine states in which an inactive slot we patched is still the one that will boot. */
    fun keepsOurPatch(status: EngineStatus?): Boolean =
        status == EngineStatus.UPDATED_NEED_REBOOT || status == EngineStatus.FINALIZING

    private fun deriveInstalled(inactive: ImageInfo?, backupDir: String): Derivation = when (inactive?.state) {
        BootImageState.PATCHED -> Derivation(Phase.PATCHED, FlowIntent.PATCHED)
        BootImageState.STOCK -> Derivation(Phase.INSTALLED, FlowIntent.PREPARED)
        else -> Derivation(Phase.FAILED, FlowIntent.PREPARED, problem = newSlotFailed(backupDir))
    }

    /**
     * Explains a missing root. Only after a reboot during a flow is there more to say than "grant root":
     * - still on the slot the flow started from (unplanned reboot while prepared, or the update rolled
     *   back): its saved patched image restores root;
     * - on the other slot after a patch: the new build booted without root; its images are in pending/;
     * - running slot unknown: point at the backup folder rather than guess a slot to flash.
     */
    fun noRootProblem(record: FlowRecord, bootId: String, slot: String, backupDir: String): Note {
        val inFlow = record.intent != FlowIntent.NONE && record.version.isNotEmpty() && record.partition.isNotEmpty()
        if (!inFlow || record.bootId == bootId) return Note(Msg.NO_ROOT)
        return when {
            slot == record.slot -> {
                val dir = BackupStore.versionDir(backupDir, record.version)
                Note(Msg.ROOT_LOST, "$dir/${BackupStore.patchedName(record.partition)}")
            }

            record.intent == FlowIntent.PATCHED && slot.isNotEmpty() ->
                Note(Msg.ROOT_LOST_AFTER_UPDATE, BackupStore.pendingDir(backupDir))

            else -> Note(Msg.ROOT_LOST_BACKUPS, backupDir)
        }
    }

    /** Whether the watcher service should run in [phase]. */
    fun needsWatcher(phase: Phase, autoPatch: Boolean): Boolean = when (phase) {
        Phase.PREPARED, Phase.INSTALLING -> true
        Phase.INSTALLED -> autoPatch
        else -> false
    }
}
