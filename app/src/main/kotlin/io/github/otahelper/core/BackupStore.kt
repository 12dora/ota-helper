package io.github.otahelper.core

/**
 * Image backups under the user-visible backup dir: `<root>/<version>/<partition>_stock.img|_ksu.img`
 * and `<root>/pending/...` for the build that is installed but not booted yet.
 *
 * Everything goes through the root shell using the /sdcard path. In init's mount namespace /sdcard is
 * the FUSE view, so MediaProvider creates the files with the owner of the parent folder and group
 * media_rw (0660; verified on device, chmod is a no-op there), which file managers can read.
 */
class BackupStore(private val shell: Shell, private val reader: DeviceReader, private val log: EventLog) {

    /**
     * Copies [source] to a temp file in [dir], verifies the copy hashes to [sha1], and only then renames it
     * to `<dir>/<name>`, so the target is never replaced by unverified bytes.
     */
    suspend fun save(source: String, dir: String, name: String, sha1: String): Boolean {
        val target = "$dir/$name"
        val temp = "$dir/.$name.tmp"
        val copy = shell.run(
            "mkdir -p ${shQuote(dir)} && cat ${shQuote(source)} > ${shQuote(temp)}",
            COPY_TIMEOUT_MS,
        )
        val copied = if (copy.ok) reader.sha1(temp) else null
        val ok = copied == sha1 && shell.run("mv -f ${shQuote(temp)} ${shQuote(target)}").ok
        log.log("backup $source -> $target: exit=${copy.code} sha1=$copied expected=$sha1 ok=$ok")
        if (!ok) shell.run("rm -f ${shQuote(temp)}")
        return ok
    }

    /** All saved builds, newest first. The pending build is not listed until it has booted. */
    suspend fun list(root: String): List<Backup> {
        if (!isValidRoot(root)) return emptyList()
        val result = shell.run(listScript(root))
        val files = result.lines.mapNotNull { line ->
            val (mtime, rel) = line.split('|', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val version = rel.substringBefore('/')
            val seconds = mtime.toLongOrNull()
            if (seconds == null || version == PENDING || !isSafeName(version)) null else Triple(version, rel, seconds)
        }
        return files.groupBy { it.first }.map { (version, entries) ->
            Backup(
                version = version,
                stockPath = entries.firstOrNull { it.second.endsWith(STOCK_SUFFIX) }?.let { "$root/${it.second}" },
                patchedPath = entries.firstOrNull { it.second.endsWith(PATCHED_SUFFIX) }?.let { "$root/${it.second}" },
                modifiedMillis = entries.maxOf { it.third } * MILLIS_PER_SECOND,
            )
        }.sortedByDescending { it.modifiedMillis }
    }

    /** Deletes all but the newest [keep] builds, never touching [protect]. */
    suspend fun prune(root: String, keep: Int, protect: Set<String>) {
        if (!isValidRoot(root)) return
        list(root).drop(keep).map { it.version }.filter { it !in protect && isSafeName(it) }.forEach {
            log.log("prune backup $it")
            shell.run("rm -rf ${shQuote("$root/$it")}")
        }
    }

    suspend fun clearPending(root: String): Boolean = isValidRoot(root) &&
        shell.run("rm -rf ${shQuote(pendingDir(root))}").ok

    enum class Promotion { DONE, NOTHING_PENDING, FAILED }

    /** Renames `pending/` to [version] once that build has booted. */
    suspend fun promotePending(root: String, version: String): Promotion {
        if (!isValidRoot(root) || !isSafeName(version)) return Promotion.FAILED
        val target = versionDir(root, version)
        val pending = pendingDir(root)
        val result = shell.run(
            "[ -d ${shQuote(pending)} ] || exit $NOTHING_PENDING_EXIT; " +
                "rm -rf ${shQuote(target)} && mv ${shQuote(pending)} ${shQuote(target)}",
        )
        log.log("promote pending -> $version: exit=${result.code}")
        return when (result.code) {
            0 -> Promotion.DONE
            NOTHING_PENDING_EXIT -> Promotion.NOTHING_PENDING
            else -> Promotion.FAILED
        }
    }

    companion object {
        const val PENDING = "pending"
        const val KEEP_VERSIONS = 3
        private const val STOCK_SUFFIX = "_stock.img"
        private const val PATCHED_SUFFIX = "_ksu.img"
        private const val COPY_TIMEOUT_MS = 120_000L
        private const val NOTHING_PENDING_EXIT = 3
        private const val MILLIS_PER_SECOND = 1000L
        private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        private val SAFE_ROOT = Regex("^/(sdcard|storage/emulated/[0-9]+)(/[A-Za-z0-9][A-Za-z0-9 ._-]*)+$")

        fun stockName(partition: String) = "$partition$STOCK_SUFFIX"

        fun patchedName(partition: String) = "$partition$PATCHED_SUFFIX"

        fun versionDir(root: String, version: String) = "$root/$version"

        fun pendingDir(root: String) = "$root/$PENDING"

        /** A folder below shared storage, without `..`, quotes or other shell-sensitive characters. */
        fun isValidRoot(root: String): Boolean = SAFE_ROOT.matches(root) && "/.." !in root

        fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name) && name != "." && name != ".."

        /** Build names like "OS4.0.5.0.XPACNXM" pass unchanged; anything else is reduced to safe characters. */
        fun safeVersion(version: String): String =
            version.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.', '_', '-').ifEmpty { "unknown" }

        private fun listScript(root: String) = """
            cd ${shQuote(root)} 2>/dev/null || exit 0
            for f in */*.img; do [ -f "${'$'}f" ] && echo "$(stat -c %Y "${'$'}f")|${'$'}f"; done
            true
        """.trimIndent()
    }
}
