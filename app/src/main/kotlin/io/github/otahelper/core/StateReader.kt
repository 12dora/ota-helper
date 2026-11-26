package io.github.otahelper.core

/** Result of one refresh. [snapshot] is null when the device could not be read (no root, unsupported). */
data class Reading(
    val derivation: Derivation,
    val bootId: String,
    val snapshot: Snapshot? = null,
    val probe: Probe? = null,
    /** One-time event found while reading, e.g. an update that did not take effect. */
    val notice: Note? = null,
    /** Null when backups could not be listed. */
    val backups: List<Backup>? = null,
)

/**
 * Reads device state, finishes a flow interrupted by a reboot, and keeps the persisted intent in line
 * with what the device actually shows.
 */
class StateReader(
    private val reader: DeviceReader,
    private val backups: BackupStore,
    private val store: StateStore,
    private val platform: Platform,
    private val log: EventLog,
) {

    suspend fun read(settings: Settings): Reading {
        val bootId = platform.bootId()
        val record = store.record()
        if (!reader.rootAvailable()) {
            val problem = PhaseRules.noRootProblem(record, bootId, platform.slotSuffix(), settings.backupDir)
            log.log("refresh: no root (${problem.msg})")
            return Reading(Derivation(Phase.NO_ROOT, record.intent, problem = problem), bootId)
        }
        val probe = reader.probe()
            ?: return Reading(Derivation(Phase.UNSUPPORTED, record.intent, problem = Note(Msg.READ_FAILED)), bootId)
        probe.unsupportedReason()?.let { reason ->
            log.log("refresh: unsupported (${reason.msg} ${reason.arg.orEmpty()})")
            return Reading(Derivation(Phase.UNSUPPORTED, record.intent, problem = reason), bootId, probe = probe)
        }
        val notice = finishReboot(record, probe, bootId, settings)
        val snapshot = readSnapshot(probe)
        val previous = store.record()
        val derivation = PhaseRules.derive(snapshot, previous, settings.backupDir)
        adoptIntent(previous, derivation, snapshot, bootId)
        log.log("refresh: ${derivation.phase} intent=${derivation.intent} engine=${snapshot.engine?.status}")
        return Reading(derivation, bootId, snapshot, probe, notice, backups.list(settings.backupDir))
    }

    /** Current slot always; update_engine only when the current slot is stock; inactive slot only once installed. */
    private suspend fun readSnapshot(probe: Probe): Snapshot {
        val current = reader.inspect(probe.partitionPath(inactive = false))
        if (current.state != BootImageState.STOCK) return Snapshot(probe, current)
        val engine = reader.engineStatus()
        val inactive = if (engine?.status == EngineStatus.UPDATED_NEED_REBOOT) {
            reader.inspect(probe.partitionPath(inactive = true))
        } else {
            null
        }
        return Snapshot(probe, current, engine, inactive)
    }

    /**
     * After [OtaController.patch] the next boot should run the other slot. If it does, the pending backup
     * becomes the new build's folder; if not, the update did not take effect. The record is cleared only
     * once that is done, so a promotion that fails (e.g. storage not ready right after boot) is retried by
     * the next refresh.
     */
    private suspend fun finishReboot(record: FlowRecord, probe: Probe, bootId: String, settings: Settings): Note? {
        if (!awaitsFinish(record, bootId)) return null
        if (probe.slotSuffix == record.slot) {
            store.saveRecord(FlowRecord())
            log.log("finish: rebooted but still on slot ${probe.slotSuffix}; update not applied")
            return Note(Msg.UPDATE_NOT_APPLIED)
        }
        val version = BackupStore.safeVersion(probe.version)
        val promotion = backups.promotePending(settings.backupDir, version)
        log.log("finish: now on slot ${probe.slotSuffix}, build ${probe.version}, backup $promotion")
        if (promotion == BackupStore.Promotion.FAILED) return null
        store.saveRecord(FlowRecord())
        backups.prune(settings.backupDir, BackupStore.KEEP_VERSIONS, setOf(version))
        platform.notifyUpdated(probe.version)
        return null
    }

    private fun awaitsFinish(record: FlowRecord, bootId: String) =
        record.intent == FlowIntent.PATCHED && record.bootId != bootId

    private fun adoptIntent(previous: FlowRecord, derivation: Derivation, snapshot: Snapshot, bootId: String) {
        // A patched record from an earlier boot is finished by finishReboot only.
        if (derivation.intent == previous.intent || awaitsFinish(previous, bootId)) return
        log.log("intent ${previous.intent} -> ${derivation.intent} (from device state)")
        val probe = snapshot.probe
        val record = when (derivation.intent) {
            FlowIntent.NONE -> FlowRecord()

            FlowIntent.PREPARED, FlowIntent.PATCHED -> previous.copy(
                intent = derivation.intent,
                slot = probe.slotSuffix,
                bootId = bootId,
                version = previous.version.ifEmpty { BackupStore.safeVersion(probe.version) },
                partition = probe.partition,
                stockSha1 = previous.stockSha1.ifEmpty { snapshot.current.sha1.orEmpty() },
            )
        }
        store.saveRecord(record)
    }
}
