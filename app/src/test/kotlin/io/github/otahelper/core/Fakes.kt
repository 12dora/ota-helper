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
 */
class FakeDevice {
    val shell = FakeShell()
    val files: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())
    private val mtimes = mutableMapOf<String, Long>()
    private var clock = 1_000L

    var slot = "_a"
    var version = "V1"
    var lkm = true
    var engineStatus = "IDLE"
    var engineCode = 0
    var engineProgress = "0"

    /** Fault injection: what ksud leaves on the partition instead of the correct image. */
    var restoreWrites: String? = null
    var patchWrites: String? = null
    var failSaves = false
    var rebooted = false

    val other get() = if (slot == "_a") "_b" else "_a"

    fun part(suffix: String) = "/dev/block/by-name/init_boot$suffix"

    fun backupOf(stock: String) = DevicePaths.KSU_BACKUP_PREFIX + sha1Of(stock)

    fun write(path: String, content: String) {
        files[path] = content
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
        shell.on("^head -c (\\d+) '([^']+)' \\| sha1sum$") { m ->
            files[m.groupValues[2]]?.let { ok("${sha1Of(it)}  -") } ?: failed()
        }
        shell.on("^sha1sum '([^']+)'$") { m ->
            files[m.groupValues[1]]?.let { ok("${sha1Of(it)}  ${m.groupValues[1]}") } ?: failed()
        }
        shell.on("update_engine_client --follow") {
            ok(
                "[INFO:update_engine_client_android.cc(222)] Successfully connected to update_engine service.\n" +
                    "[INFO:update_engine_client_android.cc(98)] onStatusUpdate(UPDATE_STATUS_$engineStatus " +
                    "($engineCode), $engineProgress)",
            )
        }
        shell.on("^mkdir -p '([^']+)' && cat '([^']+)' > '([^']+)' && mv -f '([^']+)' '([^']+)'$") { m ->
            val content = files[m.groupValues[2]]
            if (failSaves || content == null) failed() else ok().also { write(m.groupValues[5], content) }
        }
        shell.on("ksud boot-restore -f") { restore() }
        shell.on("ksud boot-patch -f( -u)? --partition '([^']+)' -o '([^']+)' --out-name ksu.img") { m ->
            patch(inactive = m.groupValues[1].isNotEmpty(), work = m.groupValues[3])
        }
        shell.on("^\\[ -d '([^']+)' \\] && rm -rf '([^']+)' && mv '([^']+)' '([^']+)'$") { m ->
            rename(m.groupValues[3], m.groupValues[4])
        }
        shell.on("^cd '([^']+)' 2>/dev/null \\|\\| exit 0") { m -> ok(listing(m.groupValues[1])) }
        shell.on("^rm -r?f '([^']+)'$") { m ->
            remove(m.groupValues[1])
            ok()
        }
        shell.on("svc power reboot") {
            rebooted = true
            ok()
        }
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
        val content = files[path] ?: return failed()
        val lines = mutableListOf("SIZE=$IMAGE_SIZE", "SHA=${sha1Of(content)}  img")
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
        val current = files[part(slot)] ?: return failed()
        if (!current.startsWith("ksu:")) return failed()
        val keep = backupOf(current.removePrefix("ksu:"))
        files.keys.filter { it.startsWith(DevicePaths.KSU_BACKUP_PREFIX) && it != keep }.forEach { files.remove(it) }
        write(part(slot), restoreWrites ?: files[keep] ?: "rebuilt-without-ksu")
        return ok("- Done!")
    }

    /** ksud boot-patch -f [-u]: patch in place, back up a stock source, write the image to the work dir. */
    private fun patch(inactive: Boolean, work: String): ShellResult {
        val target = part(if (inactive) other else slot)
        val source = files[target] ?: return failed()
        val stock = source.removePrefix("ksu:")
        if (!source.startsWith("ksu:")) write(backupOf(stock), stock)
        val patched = "ksu:$stock"
        write("$work/ksu.img", patched)
        write(target, patchWrites ?: patched)
        return ok("- Done!")
    }

    private fun rename(from: String, to: String): ShellResult {
        val moved = files.keys.filter { it.startsWith("$from/") }
        if (moved.isEmpty()) return failed()
        remove(to)
        moved.forEach { key -> write(to + key.removePrefix(from), files.getValue(key)).also { files.remove(key) } }
        return ok()
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
    val watcher = mutableListOf<Boolean>()
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun bootId() = boot

    override fun text(note: Note) = listOfNotNull(note.msg.name, note.arg).joinToString(" ")

    override fun setWatcherRunning(running: Boolean) {
        watcher += running
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
