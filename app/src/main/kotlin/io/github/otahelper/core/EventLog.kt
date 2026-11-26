package io.github.otahelper.core

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Timestamped, append-only diagnostic log. */
interface EventLog {
    fun log(message: String)

    /** The last [max] lines, oldest first. */
    fun tail(max: Int = DEFAULT_TAIL): List<String>

    companion object {
        const val DEFAULT_TAIL = 300
    }
}

/**
 * [EventLog] backed by one file. When the file grows past [maxBytes] it is cut down to its newest half,
 * so the log is bounded without losing the lines that matter for the current flow.
 */
class FileEventLog(private val file: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) : EventLog {
    private val lock = Any()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    override fun log(message: String) {
        val stamp = LocalDateTime.now().format(formatter)
        val text = message.lineSequence().joinToString("\n") { "$stamp $it" } + "\n"
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                file.appendText(text)
                if (file.length() > maxBytes) trim()
            } catch (ignored: IOException) {
                // Logging must never break the flow it describes.
            }
        }
    }

    override fun tail(max: Int): List<String> = synchronized(lock) {
        try {
            if (file.exists()) file.readLines().takeLast(max) else emptyList()
        } catch (ignored: IOException) {
            emptyList()
        }
    }

    private fun trim() {
        val lines = file.readLines()
        file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024
    }
}
