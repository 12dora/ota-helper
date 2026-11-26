package io.github.otahelper.core

/** Fixed on-device locations used by the engine. */
object DevicePaths {
    const val KSUD = "/data/adb/ksud"
    const val MAGISKBOOT = "/data/adb/ksu/bin/magiskboot"

    /** KernelSU keeps the stock image it replaced here, named by its SHA-1 (ksud defs.rs). */
    const val KSU_BACKUP_PREFIX = "/data/adb/ksu/ksu_backup_"

    /** Root-only scratch space; every user creates and removes its own subdirectory. */
    const val WORK_ROOT = "/data/local/tmp/otahelper"

    fun blockDevice(partition: String, slotSuffix: String) = "/dev/block/by-name/$partition$slotSuffix"
}

/** Facts read once per refresh. */
data class Probe(
    val abUpdate: Boolean,
    val slotSuffix: String,
    val version: String,
    /** e.g. "3.3.1"; null when ksud is missing. */
    val ksudVersion: String?,
    /** `lkm:` from `ksud debug info`; null when unknown. */
    val lkm: Boolean?,
    val partition: String,
    val magiskboot: Boolean,
    /** Block devices present under /dev/block/by-name, e.g. "init_boot_a". */
    val devices: Set<String>,
) {
    val otherSuffix: String get() = if (slotSuffix == "_a") "_b" else "_a"

    val slotLetter: String get() = slotSuffix.removePrefix("_").uppercase()

    val rootManager: String get() = ksudVersion?.let { "KernelSU $it" }.orEmpty()

    fun partitionPath(inactive: Boolean): String =
        DevicePaths.blockDevice(partition, if (inactive) otherSuffix else slotSuffix)

    /** Why this device can't be handled, or null when it can. v1 supports KernelSU LKM on A/B devices only. */
    fun unsupportedReason(): Note? = when {
        !abUpdate || slotSuffix !in SLOTS -> Note(Msg.NOT_AB)

        ksudVersion == null -> Note(Msg.NO_KERNELSU)

        lkm != true -> Note(Msg.NOT_LKM)

        partition !in PARTITIONS -> Note(Msg.UNSUPPORTED_PARTITION, partition)

        "$partition$slotSuffix" !in devices || "$partition$otherSuffix" !in devices ->
            Note(Msg.UNSUPPORTED_PARTITION, partition)

        !magiskboot -> Note(Msg.TOOLS_MISSING)

        else -> null
    }

    private companion object {
        val SLOTS = setOf("_a", "_b")
        val PARTITIONS = setOf("init_boot", "boot")
    }
}

/** What a boot image is. [sha1] and [size] cover the whole file or partition. */
data class ImageInfo(
    val state: BootImageState,
    val sha1: String?,
    val size: Long?,
    /** Content of the ramdisk's `stock_image.sha1` marker (KernelSU-patched images only). */
    val stockSha1: String?,
)

/** Read-only device queries over the root [Shell]. Nothing here writes to a block device. */
class DeviceReader(private val shell: Shell, private val log: EventLog) {

    suspend fun rootAvailable(): Boolean = shell.isRootAvailable()

    suspend fun probe(): Probe? {
        val result = run(PROBE_SCRIPT, "probe")
        if (!result.ok) return null
        val values = result.lines.mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1).trim() else null
        }
        val map = values.toMap()
        val probe = Probe(
            abUpdate = map["ab"] == "true",
            slotSuffix = map["slot"].orEmpty(),
            version = map["version"].orEmpty(),
            ksudVersion = map["ksud"]?.let { KSUD_VERSION.find(it)?.groupValues?.get(1) },
            lkm = map["lkm"]?.toBooleanStrictOrNull(),
            partition = map["partition"].orEmpty(),
            magiskboot = map["magiskboot"] == "1",
            devices = values.filter { it.first == "dev" }.map { it.second }.toSet(),
        )
        log.log("probe: $probe")
        return probe
    }

    /**
     * Copies [path] to a scratch dir, unpacks it with magiskboot and reports whether its ramdisk holds
     * `kernelsu.ko` (patched) or a plain `init` without it (stock). Anything else is [BootImageState.UNKNOWN].
     */
    suspend fun inspect(path: String): ImageInfo {
        val result = run(inspectScript(path), "inspect $path", INSPECT_TIMEOUT_MS)
        val map = result.lines.mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq > 0) line.substring(0, eq) to line.substring(eq + 1) else null
        }.toMap()
        val sha1 = map["SHA"]?.substringBefore(' ')?.takeIf(::isSha1)
        val unpacked = map["UNPACK"] == "0" && map["RAMDISK"] == "1"
        val state = when {
            sha1 == null || !unpacked -> BootImageState.UNKNOWN
            map["KSU"] == "0" -> BootImageState.PATCHED
            map["KSU"] == "1" && map["INIT"] == "0" -> BootImageState.STOCK
            else -> BootImageState.UNKNOWN
        }
        val info = ImageInfo(state, sha1, map["SIZE"]?.toLongOrNull(), map["MARKER"]?.trim()?.takeIf(::isSha1))
        log.log("inspect $path: $info")
        return info
    }

    suspend fun sha1(path: String): String? = run("sha1sum ${shQuote(path)}", "sha1 $path", HASH_TIMEOUT_MS).firstSha1()

    /** SHA-1 of the first [length] bytes of [path]: a flashed image may be shorter than its partition. */
    suspend fun sha1Prefix(path: String, length: Long): String? {
        // pipefail: a failing head must not turn into the SHA-1 of empty input.
        val command = "set -o pipefail; head -c $length ${shQuote(path)} | sha1sum"
        return run(command, "sha1 $length bytes of $path", HASH_TIMEOUT_MS).firstSha1()
    }

    /**
     * One-shot update_engine status. `--follow` prints the current status as soon as it connects, then
     * blocks while idle, so the client runs in the background until its first status line (at most
     * ~6 s while the lazy update_engine service starts) and is then killed directly (killing a
     * `timeout` wrapper would orphan it; verified on device), with a delayed kill as a backstop.
     */
    suspend fun engineStatus(): EngineUpdate? {
        val result = run(ENGINE_QUERY, "update_engine status", ENGINE_TIMEOUT_MS)
        val update = EngineStatusParser.parseLast(result.stdout)
        log.log("update_engine: ${update ?: "no status"}")
        return update
    }

    private suspend fun run(command: String, what: String, timeoutMs: Long = Shell.DEFAULT_TIMEOUT_MS): ShellResult {
        val result = shell.run(command, timeoutMs)
        if (!result.ok) log.log("$what: exit ${result.code} ${result.stderr.take(LOG_SNIPPET)}")
        return result
    }

    private fun ShellResult.firstSha1(): String? = if (ok) stdout.trim().substringBefore(' ').takeIf(::isSha1) else null

    companion object {
        private const val INSPECT_TIMEOUT_MS = 60_000L
        private const val HASH_TIMEOUT_MS = 60_000L
        private const val ENGINE_TIMEOUT_MS = 15_000L
        private const val LOG_SNIPPET = 300
        private val SHA1 = Regex("^[0-9a-f]{40}$")
        private val KSUD_VERSION = Regex("""ksud\s+(\S+)""")

        private val ENGINE_QUERY = """
            mkdir -p ${DevicePaths.WORK_ROOT}; f=${DevicePaths.WORK_ROOT}/engine-$$
            update_engine_client --follow > "${'$'}f" 2>&1 &
            p=$!; ( sleep 10; kill ${'$'}p ) >/dev/null 2>&1 & k=$!
            i=0
            while [ ${'$'}i -lt 60 ] && ! grep -q onStatusUpdate "${'$'}f"; do sleep 0.1; i=$((i+1)); done
            kill ${'$'}p ${'$'}k 2>/dev/null; cat "${'$'}f"; rm -f "${'$'}f"
        """.trimIndent()

        private val PROBE_SCRIPT = """
            echo "ab=$(getprop ro.build.ab_update)"
            echo "slot=$(getprop ro.boot.slot_suffix)"
            echo "version=$(getprop ro.build.version.incremental)"
            if [ -x ${DevicePaths.KSUD} ]; then
              echo "ksud=$(${DevicePaths.KSUD} -V 2>/dev/null)"
              echo "partition=$(${DevicePaths.KSUD} boot-info default-partition 2>/dev/null)"
              ${DevicePaths.KSUD} debug info 2>/dev/null | grep -E '^lkm:' | sed 's/: */=/'
            fi
            [ -x ${DevicePaths.MAGISKBOOT} ] && echo "magiskboot=1"
            for s in _a _b; do for p in init_boot boot; do
              [ -e /dev/block/by-name/${'$'}p${'$'}s ] && echo "dev=${'$'}p${'$'}s"
            done; done
            true
        """.trimIndent()

        fun isSha1(value: String): Boolean = SHA1.matches(value)

        fun inspectScript(path: String): String {
            val mb = DevicePaths.MAGISKBOOT
            return """
                w=${DevicePaths.WORK_ROOT}/inspect-$$
                rm -rf "${'$'}w"; mkdir -p "${'$'}w" && cd "${'$'}w" || exit 1
                cat ${shQuote(path)} > img || { cd /; rm -rf "${'$'}w"; exit 1; }
                echo "SIZE=$(stat -c %s img)"
                echo "SHA=$(sha1sum img)"
                $mb unpack img >/dev/null 2>&1; echo "UNPACK=$?"
                if [ -f ramdisk.cpio ]; then
                  echo "RAMDISK=1"
                  $mb cpio ramdisk.cpio 'exists kernelsu.ko' >/dev/null 2>&1; echo "KSU=$?"
                  $mb cpio ramdisk.cpio 'exists init' >/dev/null 2>&1; echo "INIT=$?"
                  $mb cpio ramdisk.cpio 'extract stock_image.sha1 marker' >/dev/null 2>&1 && echo "MARKER=$(cat marker)"
                fi
                cd /; rm -rf "${'$'}w"
            """.trimIndent()
        }
    }
}
