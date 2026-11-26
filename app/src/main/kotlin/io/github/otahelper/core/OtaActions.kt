package io.github.otahelper.core

/** Thrown by an action step whose precondition or check failed; nothing after it runs. */
class ActionFailure(val note: Note) : Exception("${note.msg} ${note.arg.orEmpty()}")

/** Input of one action: device state read right before it, under the action lock. */
data class ActionContext(val reading: Reading, val settings: Settings)

private fun fail(msg: Msg, arg: String? = null): Nothing = throw ActionFailure(Note(msg, arg))

private inline fun ensure(condition: Boolean, msg: Msg, arg: () -> String? = { null }) {
    if (!condition) fail(msg, arg())
}

/**
 * The flashing flows. Each one re-checks every precondition against fresh device state, saves what it
 * is about to overwrite, flashes once through [Flasher], and verifies the result. A failed check throws
 * [ActionFailure] and nothing after it runs, in particular no further flash.
 */
class OtaActions(
    private val reader: DeviceReader,
    private val flasher: Flasher,
    private val backups: BackupStore,
    private val store: StateStore,
    private val log: EventLog,
) {

    /** IDLE: back up stock + patched images of the running build, then restore stock to the current slot. */
    suspend fun prepare(ctx: ActionContext): Note {
        val snapshot = expectPhase(ctx, Phase.IDLE)
        val probe = snapshot.probe
        val root = backupRoot(ctx)
        val current = snapshot.current
        val currentPath = probe.partitionPath(inactive = false)
        val currentSha = current.sha1 ?: fail(Msg.UNKNOWN_IMAGE)
        val stockSha = current.stockSha1 ?: fail(Msg.BACKUP_MISSING)
        val stockPath = DevicePaths.KSU_BACKUP_PREFIX + stockSha
        val stock = reader.inspect(stockPath)
        ensure(stock.sha1 != null, Msg.BACKUP_MISSING)
        val stockSize = stock.size ?: fail(Msg.BACKUP_INVALID)
        // Same size as the partition, so after flashing the whole partition must hash to the stock SHA-1.
        ensure(stock.sha1 == stockSha && stock.state == BootImageState.STOCK, Msg.BACKUP_INVALID)
        ensure(stockSize == current.size, Msg.BACKUP_INVALID)

        val version = BackupStore.safeVersion(probe.version)
        val dir = BackupStore.versionDir(root, version)
        val savedStock = backups.save(stockPath, dir, BackupStore.stockName(probe.partition), stockSha)
        ensure(savedStock, Msg.SAVE_FAILED) { dir }
        val savedPatched = backups.save(currentPath, dir, BackupStore.patchedName(probe.partition), currentSha)
        ensure(savedPatched, Msg.SAVE_FAILED) { dir }
        ensure(reader.sha1(currentPath) == currentSha, Msg.STATE_CHANGED)

        store.saveRecord(
            FlowRecord(FlowIntent.PREPARED, probe.slotSuffix, ctx.reading.bootId, version, probe.partition, stockSha),
        )
        log.log("prepare: restoring stock $stockSha to $currentPath")
        when (flasher.restoreStock(currentPath, stockSha, stockSize)) {
            stockSha -> Unit
            currentSha -> fail(Msg.FLASH_FAILED)
            else -> fail(Msg.VERIFY_FAILED)
        }
        backups.prune(root, BackupStore.KEEP_VERSIONS, setOf(version))
        return Note(Msg.PREPARE_DONE)
    }

    /** PREPARED (no update installed): patch the current slot again. */
    suspend fun cancel(ctx: ActionContext): Note {
        val phase = ctx.reading.derivation.phase
        ensure(phase != Phase.INSTALLING, Msg.UPDATE_RUNNING)
        ensure(phase != Phase.INSTALLED && phase != Phase.PATCHED, Msg.UPDATE_INSTALLED)
        val snapshot = expectPhase(ctx, Phase.PREPARED)
        val engine = snapshot.engine?.status
        ensure(engine != null && engine != EngineStatus.UNKNOWN, Msg.ENGINE_UNKNOWN)
        val probe = snapshot.probe
        val path = probe.partitionPath(inactive = false)
        val sha = snapshot.current.sha1 ?: fail(Msg.UNKNOWN_IMAGE)
        ensure(reader.sha1(path) == sha, Msg.STATE_CHANGED)

        log.log("cancel: patching current slot $path")
        val outcome = flasher.patch(probe.partition, path, inactiveSlot = false, stockSha1 = sha)
        flasher.cleanup(outcome.workDir)
        checkFlash(outcome, sha)
        store.saveRecord(FlowRecord())
        return Note(Msg.CANCEL_DONE)
    }

    /** INSTALLED: back up the new build's stock image, then patch the inactive slot. */
    suspend fun patch(ctx: ActionContext): Note {
        val snapshot = ctx.reading.snapshot ?: fail(Msg.WRONG_PHASE)
        ensure(ctx.reading.derivation.phase != Phase.PATCHED, Msg.WRONG_PHASE)
        ensure(snapshot.engine?.status == EngineStatus.UPDATED_NEED_REBOOT, Msg.NOT_INSTALLED)
        expectPhase(ctx, Phase.INSTALLED)
        val inactive = snapshot.inactive
        ensure(inactive?.state == BootImageState.STOCK, Msg.UNKNOWN_IMAGE)
        val probe = snapshot.probe
        val path = probe.partitionPath(inactive = true)
        val sha = inactive?.sha1 ?: fail(Msg.UNKNOWN_IMAGE)
        val root = backupRoot(ctx)
        val pending = BackupStore.pendingDir(root)
        ensure(backups.clearPending(root), Msg.SAVE_FAILED) { pending }
        val savedStock = backups.save(path, pending, BackupStore.stockName(probe.partition), sha)
        ensure(savedStock, Msg.SAVE_FAILED) { pending }
        ensure(reader.sha1(path) == sha, Msg.STATE_CHANGED)
        ensure(reader.engineStatus()?.status == EngineStatus.UPDATED_NEED_REBOOT, Msg.NOT_INSTALLED)

        log.log("patch: patching inactive slot $path (stock $sha)")
        val outcome = flasher.patch(probe.partition, path, inactiveSlot = true, stockSha1 = sha)
        try {
            checkFlash(outcome, sha)
            val image = outcome.imagePath
            val imageSha = outcome.imageSha1
            val saved = image != null && imageSha != null &&
                backups.save(image, pending, BackupStore.patchedName(probe.partition), imageSha)
            if (!saved) log.log("patch: flashed and verified, but saving the patched image failed")
        } finally {
            flasher.cleanup(outcome.workDir)
        }
        val previous = store.record()
        store.saveRecord(
            previous.copy(
                intent = FlowIntent.PATCHED,
                slot = probe.slotSuffix,
                bootId = ctx.reading.bootId,
                partition = probe.partition,
            ),
        )
        return Note(Msg.PATCH_DONE)
    }

    /** PATCHED: reboot into the patched new slot. */
    suspend fun reboot(ctx: ActionContext): Note {
        expectPhase(ctx, Phase.PATCHED)
        val result = flasher.reboot()
        // A positive exit code means both reboot commands refused; negative means the shell went away.
        ensure(result.code <= 0, Msg.REBOOT_FAILED)
        return Note(Msg.REBOOTING)
    }

    private fun checkFlash(outcome: PatchOutcome, stockSha: String) {
        if (outcome.ok) return
        // Unchanged partition: ksud stopped before writing. Anything else: written but not as expected.
        fail(if (outcome.partitionSha1 == stockSha) Msg.FLASH_FAILED else Msg.VERIFY_FAILED)
    }

    private fun expectPhase(ctx: ActionContext, phase: Phase): Snapshot {
        ensure(ctx.reading.derivation.phase == phase, Msg.WRONG_PHASE)
        return ctx.reading.snapshot ?: fail(Msg.WRONG_PHASE)
    }

    private fun backupRoot(ctx: ActionContext): String {
        val root = ctx.settings.backupDir
        ensure(BackupStore.isValidRoot(root), Msg.BACKUP_DIR_INVALID)
        return root
    }
}
