package io.github.otahelper.core

import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** [Shell] answering commands through regex rules; unmatched commands fail the test. */
class FakeShell : Shell {
    private class Rule(val pattern: Regex, val respond: (MatchResult) -> ShellResult)

    private val rules = mutableListOf<Rule>()
    private val inFlight = AtomicInteger()
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var maxInFlight = 0
        private set
    var root = true

    fun on(pattern: String, respond: (MatchResult) -> ShellResult) {
        rules += Rule(Regex(pattern), respond)
    }

    fun count(pattern: String): Int {
        val regex = Regex(pattern)
        return commands.toList().count { regex.containsMatchIn(it) }
    }

    override suspend fun run(command: String, timeoutMs: Long): ShellResult {
        val now = inFlight.incrementAndGet()
        maxInFlight = maxOf(maxInFlight, now)
        try {
            commands += command
            delay(1)
            for (rule in rules) {
                val match = rule.pattern.find(command) ?: continue
                return rule.respond(match)
            }
            throw AssertionError("unexpected command: $command")
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override suspend fun isRootAvailable(): Boolean = root
}

fun ok(stdout: String = "") = ShellResult(0, stdout, "")

fun failed(code: Int = 1) = ShellResult(code, "", "error")

fun sha1Of(content: String): String =
    MessageDigest.getInstance("SHA-1").digest(content.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }

/**
 * A simulated device behind [FakeShell]. Files and partitions hold content tokens: "stock:<build>" is a
 * stock image, "ksu:<stock token>" the KernelSU-patched form of it, anything else is unrecognized.
 * Every file has a size (default [IMAGE_SIZE]); flashing a shorter image leaves the partition's old tail
 * in place, so only `head -c <image size>` of the partition matches the image, like on a real device.
 */
class FakeDevice {
    val shell = FakeShell()
    val files: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())
    val sizes: MutableMap<String, Long> = Collections.synchronizedMap(mutableMapOf())
    private val prefixes = mutableMapOf<String, Pair<Long, String>>()
    private val mtimes = mutableMapOf<String, Long>()
    private var clock = 1_000L

    var slot = "_a"
    var version = "V1"
    var lkm = true
    var engineStatus = "IDLE"
    var engineCode = 0
    var engineProgress = "0"

    /** update_engine answers without a status line. */
    var engineSilent = false
    var engineQueries = 0
        private set

    /** Runs before each update_engine query with its 1-based number, e.g. to change the status mid-action. */
    var beforeEngineQuery: (Int) -> Unit = {}

    /** Fault injection: what ksud leaves on the partition instead of the correct image. */
    var restoreWrites: String? = null
    var patchWrites: String? = null

    /** ksud boot-patch exits non-zero before flashing, so it writes neither the partition nor ksu.img. */
    var patchFailsBeforeFlash = false

    /** Size of the image ksud boot-patch builds. */
    var patchedSize = IMAGE_SIZE
    var failSaves = false

    /** Copies complete but with wrong content (bad storage). */
    var corruptSaves = false
    var failPromote = false
    var rebootCode = 0
    var rebooted = false

    val other get() = if (slot == "_a") "_b" else "_a"

    fun part(suffix: String) = "/dev/block/by-name/init_boot$suffix"

    fun backupOf(stock: String) = DevicePaths.KSU_BACKUP_PREFIX + sha1Of(stock)

    fun write(path: String, content: String, size: Long = IMAGE_SIZE) {
        files[path] = content
        sizes[path] = size
        prefixes.remove(path)
        mtimes[path] = clock++
    }

    fun engine(status: String, code: Int, progress: String = "0") {
        engineStatus = status
        engineCode = code
        engineProgress = progress
    }

    /** Running build V1 on slot A, patched by KernelSU, with the stock backup in /data/adb/ksu. */
    fun installPatchedV1() {
        write(part("_a"), "ksu:stock:V1")
        write(part("_b"), "stock:V0")
        write(backupOf("stock:V1"), "stock:V1")
    }

    init {
        shell.on("getprop ro.build.ab_update") { ok(probeOutput()) }
        shell.on("cat '([^']+)' > img") { m -> inspect(m.groupValues[1]) }
        shell.on("^set -o pipefail; head -c (\\d+) '([^']+)' \\| sha1sum$") { m ->
            prefixSha(m.groupValues[2], m.groupValues[1].toLong())?.let { ok("$it  -") } ?: failed()
        }
        shell.on("^sha1sum '([^']+)'$") { m ->
            files[m.groupValues[1]]?.let { ok("${sha1Of(it)}  ${m.groupValues[1]}") } ?: failed()
        }
        shell.on("update_engine_client --follow") { engineOutput() }
        shell.on("^mkdir -p '([^']+)' && cat '([^']+)' > '([^']+)'$") { m ->
            val source = m.groupValues[2]
            val content = files[source]
            if (failSaves || content == null) {
                failed()
            } else {
                write(m.groupValues[3], if (corruptSaves) "corrupt" else content, sizeOf(source))
                ok()
            }
        }
        shell.on("^mv -f '([^']+)' '([^']+)'$") { m -> rename(m.groupValues[1], m.groupValues[2]) }
        shell.on("ksud boot-restore -f") { restore() }
        shell.on("ksud boot-patch -f( -u)? --partition '([^']+)' -o '([^']+)' --out-name ksu.img") { m ->
            patch(inactive = m.groupValues[1].isNotEmpty(), work = m.groupValues[3])
        }
        shell.on("^\\[ -d '([^']+)' \\] \\|\\| exit 3; rm -rf '([^']+)' && mv '([^']+)' '([^']+)'$") { m ->
            promote(m.groupValues[1], m.groupValues[2])
        }
        shell.on("^cd '([^']+)' 2>/dev/null \\|\\| exit 0") { m -> ok(listing(m.groupValues[1])) }
        shell.on("^rm -r?f '([^']+)'$") { m ->
            remove(m.groupValues[1])
            ok()
        }
        shell.on("svc power reboot") {
            rebooted = rebootCode == 0
            ShellResult(rebootCode, "", "")
        }
    }

    fun sizeOf(path: String) = sizes[path] ?: IMAGE_SIZE

    private fun prefixSha(path: String, length: Long): String? {
        val content = files[path] ?: return null
        val prefix = prefixes[path]
        return when {
            prefix != null && prefix.first == length -> sha1Of(prefix.second)
            length == sizeOf(path) -> sha1Of(content)
            else -> sha1Of("$content@$length")
        }
    }

    /** Writes [image] of [imageSize] bytes over a partition, keeping its old tail when the image is shorter. */
    private fun flash(target: String, image: String, imageSize: Long) {
        val partitionSize = sizeOf(target)
        if (imageSize < partitionSize) {
            write(target, "$image$TAIL", partitionSize)
            prefixes[target] = imageSize to image
        } else {
            write(target, image, partitionSize)
        }
    }

    private fun engineOutput(): ShellResult {
        engineQueries++
        beforeEngineQuery(engineQueries)
        val connected = "[INFO:update_engine_client_android.cc(222)] Successfully connected to update_engine service."
        if (engineSilent) return ok(connected)
        return ok(
            "$connected\n[INFO:update_engine_client_android.cc(98)] onStatusUpdate(UPDATE_STATUS_$engineStatus " +
                "($engineCode), $engineProgress)",
        )
    }

    private fun probeOutput() = listOf(
        "ab=true",
        "slot=$slot",
        "version=$version",
        "ksud=ksud 3.3.1 (uapi: 5)",
        "partition=init_boot",
        "lkm=$lkm",
        "magiskboot=1",
        "dev=init_boot_a",
        "dev=init_boot_b",
        "dev=boot_a",
        "dev=boot_b",
    ).joinToString("\n")

    private fun inspect(path: String): ShellResult {
        val full = files[path] ?: return failed()
        val content = full.removeSuffix(TAIL)
        val lines = mutableListOf("SIZE=${sizeOf(path)}", "SHA=${sha1Of(full)}  img")
        when {
            content.startsWith("ksu:") -> lines += listOf(
                "UNPACK=0",
                "RAMDISK=1",
                "KSU=0",
                "INIT=0",
                "MARKER=${sha1Of(content.removePrefix("ksu:"))}",
            )

            content.startsWith("stock:") -> lines += listOf("UNPACK=0", "RAMDISK=1", "KSU=1", "INIT=0")

            else -> lines += "UNPACK=1"
        }
        return ok(lines.joinToString("\n"))
    }

    /** ksud boot-restore -f: flash the backup named by the current slot's marker, drop other backups. */
    private fun restore(): ShellResult {
        val current = files[part(slot)]?.removeSuffix(TAIL) ?: return failed()
        if (!current.startsWith("ksu:")) return failed()
        val keep = backupOf(current.removePrefix("ksu:"))
        files.keys.filter { it.startsWith(DevicePaths.KSU_BACKUP_PREFIX) && it != keep }.forEach { files.remove(it) }
        flash(part(slot), restoreWrites ?: files[keep] ?: "rebuilt-without-ksu", sizeOf(keep))
        return ok("- Done!")
    }

    /**
     * ksud boot-patch -f [-u]: patch in place, back up a stock source, then write the image to the work
     * dir (ksud writes the output only after the flash succeeded).
     */
    private fun patch(inactive: Boolean, work: String): ShellResult {
        if (patchFailsBeforeFlash) return ShellResult(1, "- Patch Error: Failed to load kernelsu.ko", "")
        val target = part(if (inactive) other else slot)
        val source = files[target]?.removeSuffix(TAIL) ?: return failed()
        val stock = source.removePrefix("ksu:")
        if (!source.startsWith("ksu:")) write(backupOf(stock), stock)
        val patched = "ksu:$stock"
        flash(target, patchWrites ?: patched, patchedSize)
        write("$work/ksu.img", patched, patchedSize)
        return ok("- Done!")
    }

    private fun rename(from: String, to: String): ShellResult {
        val content = files[from] ?: return failed()
        write(to, content, sizeOf(from))
        files.remove(from)
        return ok()
    }

    private fun promote(pending: String, target: String): ShellResult {
        val moved = files.keys.filter { it.startsWith("$pending/") }
        return when {
            moved.isEmpty() -> failed(code = 3)

            failPromote -> failed()

            else -> {
                remove(target)
                moved.forEach { key -> rename(key, target + key.removePrefix(pending)) }
                ok()
            }
        }
    }

    private fun remove(prefix: String) {
        files.keys.filter { it == prefix || it.startsWith("$prefix/") }.forEach { files.remove(it) }
    }

    private fun listing(root: String) = files.keys.filter { it.startsWith("$root/") && it.endsWith(".img") }
        .map { it.removePrefix("$root/") }
        .filter { it.count { c -> c == '/' } == 1 && !it.substringAfter('/').startsWith(".") }
        .joinToString("\n") { "${mtimes.getValue("$root/$it")}|$it" }

    companion object {
        const val IMAGE_SIZE = 8_388_608L
        private const val TAIL = "+old-tail"
    }
}

class MemoryStore(var record: FlowRecord = FlowRecord(), var settings: Settings = Settings()) : StateStore {
    override fun record() = record

    override fun saveRecord(record: FlowRecord) {
        this.record = record
    }

    override fun settings() = settings

    override fun saveSettings(settings: Settings) {
        this.settings = settings
    }
}

class FakePlatform : Platform {
    var boot = "boot-1"
    var slot = "_a"

    /** The system refuses foreground-service starts (app in background). */
    var refuseStarts = false
    val watcher: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun bootId() = boot

    override fun slotSuffix() = slot

    override fun text(note: Note) = listOfNotNull(note.msg.name, note.arg).joinToString(" ")

    override fun setWatcherRunning(running: Boolean): Boolean {
        watcher += running
        return !(running && refuseStarts)
    }

    override fun notifyPatched() {
        events += "patched"
    }

    override fun notifyUpdated(version: String) {
        events += "updated $version"
    }

    override fun notifyAutoPatchFailed(message: String) {
        events += "autopatch failed $message"
    }

    override suspend fun <T> keepAwake(block: suspend () -> T): T = block()
}

class MemoryLog : EventLog {
    val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun log(message: String) {
        lines += message
    }

    override fun tail(max: Int): List<String> = lines.toList().takeLast(max)
}
