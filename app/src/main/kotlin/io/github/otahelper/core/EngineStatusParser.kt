package io.github.otahelper.core

/** One `onStatusUpdate` report from update_engine. [progress] is the raw per-stage value, 0..1. */
data class EngineUpdate(val status: EngineStatus, val progress: Float)

/**
 * Parses `update_engine_client --follow` output, e.g.
 * `[INFO:update_engine_client_android.cc(98)] onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 0.42)`.
 */
object EngineStatusParser {
    private val STATUS_LINE = Regex("""onStatusUpdate\(UPDATE_STATUS_([A-Z_]+) \((\d+)\), ([^)\s]+)\)""")

    /** update_engine's UpdateStatus codes, in numeric order. */
    private val BY_CODE = listOf(
        EngineStatus.IDLE,
        EngineStatus.CHECKING_FOR_UPDATE,
        EngineStatus.UPDATE_AVAILABLE,
        EngineStatus.DOWNLOADING,
        EngineStatus.VERIFYING,
        EngineStatus.FINALIZING,
        EngineStatus.UPDATED_NEED_REBOOT,
        EngineStatus.REPORTING_ERROR_EVENT,
        EngineStatus.ATTEMPTING_ROLLBACK,
        EngineStatus.DISABLED,
        EngineStatus.NEED_PERMISSION_TO_UPDATE,
        EngineStatus.CLEANUP_PREVIOUS_UPDATE,
    )

    /** Share of the overall bar each install stage covers; they add up to 1. */
    private const val APPLY_SHARE = 0.85f
    private const val VERIFY_SHARE = 0.05f
    private const val FINALIZE_SHARE = 0.10f

    fun parseLine(line: String): EngineUpdate? {
        val match = STATUS_LINE.find(line) ?: return null
        val (name, codeText, progressText) = match.destructured
        val status = codeText.toIntOrNull()?.let { BY_CODE.getOrNull(it) }
            ?: EngineStatus.entries.firstOrNull { it.name == name }
            ?: EngineStatus.UNKNOWN
        val raw = progressText.toFloatOrNull()?.takeIf { !it.isNaN() } ?: 0f
        return EngineUpdate(status, raw.coerceIn(0f, 1f))
    }

    /** The last status reported in [output], or null when there is none. */
    fun parseLast(output: String): EngineUpdate? = output.lineSequence().mapNotNull(::parseLine).lastOrNull()

    private val INSTALL_STAGES = setOf(
        EngineStatus.CLEANUP_PREVIOUS_UPDATE,
        EngineStatus.DOWNLOADING,
        EngineStatus.VERIFYING,
        EngineStatus.FINALIZING,
    )

    fun isInstalling(status: EngineStatus): Boolean = status in INSTALL_STAGES

    /**
     * Maps the per-stage progress onto one 0..1 bar across applying, verifying and finalizing, so the
     * bar never jumps back to 0 between stages. Null when [update] is not an install stage.
     */
    fun overallProgress(update: EngineUpdate): Float? = when (update.status) {
        EngineStatus.CLEANUP_PREVIOUS_UPDATE -> 0f
        EngineStatus.DOWNLOADING -> update.progress * APPLY_SHARE
        EngineStatus.VERIFYING -> APPLY_SHARE + update.progress * VERIFY_SHARE
        EngineStatus.FINALIZING -> APPLY_SHARE + VERIFY_SHARE + update.progress * FINALIZE_SHARE
        else -> null
    }
}
