package io.github.otahelper.core

/**
 * Result of a patch-and-flash. [partitionSha1] is the partition's hash afterwards (over the image size);
 * [imagePath] is the verified patched image left in [workDir] for backing up.
 */
data class PatchOutcome(
    val ok: Boolean,
    val workDir: String,
    val partitionSha1: String?,
    val imagePath: String? = null,
    val imageSha1: String? = null,
)

/**
 * The only code that writes boot partitions, always through ksud. Every flash is followed by
 * re-reading the partition; callers must stop on any false/failed result.
 */
class Flasher(private val shell: Shell, private val reader: DeviceReader, private val log: EventLog) {

    /**
     * `ksud boot-restore -f`: ksud reads `stock_image.sha1` from the current slot's ramdisk and writes
     * `/data/adb/ksu/ksu_backup_<sha1>` back to it (deleting other ksu_backup_* files as a side effect).
     * Returns the partition's SHA-1 over [stockSize] bytes afterwards; success means it equals [stockSha1].
     */
    suspend fun restoreStock(partitionPath: String, stockSha1: String, stockSize: Long): String? {
        val work = "${DevicePaths.WORK_ROOT}/restore"
        val command = "rm -rf ${shQuote(work)} && mkdir -p ${shQuote(work)} && cd ${shQuote(work)} && " +
            "${DevicePaths.KSUD} boot-restore -f 2>&1"
        val result = flash(command)
        shell.run("rm -rf ${shQuote(work)}")
        val after = reader.sha1Prefix(partitionPath, stockSize)
        val ok = after == stockSha1
        log.log("restore verify: partition=$after expected=$stockSha1 ksud exit=${result.code} -> ${pass(ok)}")
        return after
    }

    /**
     * `ksud boot-patch -f [-u] --partition <p>`: patches the current (or, with [inactiveSlot], the other)
     * slot's partition in place and also writes the image to the work dir. Success means the partition
     * matches the written image, differs from [stockSha1], and unpacks as KernelSU-patched.
     */
    suspend fun patch(
        partition: String,
        partitionPath: String,
        inactiveSlot: Boolean,
        stockSha1: String,
    ): PatchOutcome {
        val work = "${DevicePaths.WORK_ROOT}/patch"
        val image = "$work/$OUT_NAME"
        val ota = if (inactiveSlot) " -u" else ""
        val command = "rm -rf ${shQuote(work)} && mkdir -p ${shQuote(work)} && cd ${shQuote(work)} && " +
            "${DevicePaths.KSUD} boot-patch -f$ota --partition ${shQuote(partition)} " +
            "-o ${shQuote(work)} --out-name $OUT_NAME 2>&1"
        val result = flash(command)
        val built = reader.inspect(image)
        val builtSha = built.sha1
        val after = built.size?.let { reader.sha1Prefix(partitionPath, it) }
        val flashed = if (builtSha != null && after == builtSha) reader.inspect(partitionPath).state else null
        val ok = listOf(
            result.ok,
            built.state == BootImageState.PATCHED,
            after == builtSha,
            after != stockSha1,
            flashed == BootImageState.PATCHED,
        ).all { it }
        log.log(
            "patch verify: image=${built.state}/$builtSha partition=$after/$flashed stock=$stockSha1 " +
                "ksud exit=${result.code} -> ${pass(ok)}",
        )
        return PatchOutcome(ok, work, after, image.takeIf { ok }, builtSha.takeIf { ok })
    }

    suspend fun cleanup(workDir: String) {
        if (workDir.startsWith(DevicePaths.WORK_ROOT + "/")) shell.run("rm -rf ${shQuote(workDir)}")
    }

    /** Normal reboot; update_engine already marked the new slot active. The shell may die before replying. */
    suspend fun reboot(): ShellResult {
        log.log("reboot")
        return shell.run("sync; svc power reboot || /system/bin/reboot", REBOOT_TIMEOUT_MS)
    }

    private suspend fun flash(command: String): ShellResult {
        log.log("flash: $command")
        val result = shell.run(command, FLASH_TIMEOUT_MS)
        result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { log.log("  ksud: $it") }
        if (result.stderr.isNotBlank()) log.log("  stderr: ${result.stderr.take(LOG_SNIPPET)}")
        return result
    }

    private fun pass(ok: Boolean) = if (ok) "OK" else "FAILED"

    private companion object {
        const val OUT_NAME = "ksu.img"
        const val FLASH_TIMEOUT_MS = 300_000L
        const val REBOOT_TIMEOUT_MS = 20_000L
        const val LOG_SNIPPET = 500
    }
}
