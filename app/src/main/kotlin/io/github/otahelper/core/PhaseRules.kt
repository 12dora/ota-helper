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
 * Phase derivation. The phase comes from device state alone, so it heals itself after the app was
 * killed or the device rebooted mid-flow; the persisted record is only needed to finish a reboot
 * ([OtaEngine] handles that) and to explain a lost root.
 *
 * | current slot | update_engine        | inactive slot | phase      |
 * |--------------|----------------------|---------------|------------|
 * | patched      | (not read)           | (not read)    | IDLE       |
 * | stock        | applying/verifying/… | (not read)    | INSTALLING |
 * | stock        | UPDATED_NEED_REBOOT  | stock         | INSTALLED  |
 * | stock        | UPDATED_NEED_REBOOT  | patched       | PATCHED    |
 * | stock        | anything else        | (not read)    | PREPARED   |
 * | unknown      | –                    | –             | UNSUPPORTED|
 */
object PhaseRules {

    fun derive(snapshot: Snapshot, intent: FlowIntent): Derivation {
        snapshot.probe.unsupportedReason()?.let { return Derivation(Phase.UNSUPPORTED, intent, problem = it) }
        return when (snapshot.current.state) {
            BootImageState.PATCHED -> Derivation(Phase.IDLE, FlowIntent.NONE)
            BootImageState.STOCK -> deriveStock(snapshot)
            BootImageState.UNKNOWN -> Derivation(Phase.UNSUPPORTED, intent, problem = Note(Msg.UNKNOWN_IMAGE))
        }
    }

    private fun deriveStock(snapshot: Snapshot): Derivation {
        val engine = snapshot.engine
        return when {
            engine == null || engine.status == EngineStatus.UNKNOWN ->
                Derivation(Phase.PREPARED, FlowIntent.PREPARED, problem = Note(Msg.ENGINE_UNKNOWN))

            engine.status == EngineStatus.UPDATED_NEED_REBOOT -> deriveInstalled(snapshot.inactive)

            EngineStatusParser.isInstalling(engine.status) ->
                Derivation(Phase.INSTALLING, FlowIntent.PREPARED, EngineStatusParser.overallProgress(engine))

            engine.status == EngineStatus.REPORTING_ERROR_EVENT ->
                Derivation(Phase.PREPARED, FlowIntent.PREPARED, problem = Note(Msg.INSTALL_FAILED))

            else -> Derivation(Phase.PREPARED, FlowIntent.PREPARED)
        }
    }

    private fun deriveInstalled(inactive: ImageInfo?): Derivation = when (inactive?.state) {
        BootImageState.PATCHED -> Derivation(Phase.PATCHED, FlowIntent.PATCHED)
        BootImageState.STOCK -> Derivation(Phase.INSTALLED, FlowIntent.PREPARED)
        else -> Derivation(Phase.INSTALLED, FlowIntent.PREPARED, problem = Note(Msg.UNKNOWN_IMAGE))
    }

    /** Explains a missing root: after an unplanned reboot in PREPARED the saved patched image restores it. */
    fun noRootProblem(record: FlowRecord, bootId: String, backupDir: String): Note {
        val rebootedWhilePrepared = record.intent == FlowIntent.PREPARED && record.bootId != bootId
        return if (rebootedWhilePrepared && record.version.isNotEmpty() && record.partition.isNotEmpty()) {
            val dir = BackupStore.versionDir(backupDir, record.version)
            Note(Msg.ROOT_LOST, "$dir/${BackupStore.patchedName(record.partition)}")
        } else {
            Note(Msg.NO_ROOT)
        }
    }

    /** Whether the watcher service should run in [phase]. */
    fun needsWatcher(phase: Phase, autoPatch: Boolean): Boolean = when (phase) {
        Phase.PREPARED, Phase.INSTALLING -> true
        Phase.INSTALLED -> autoPatch
        else -> false
    }
}
