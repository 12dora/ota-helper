@file:Suppress("MagicNumber")

package io.github.otahelper.ui

import android.content.Context
import io.github.otahelper.R
import io.github.otahelper.core.ActionResult
import io.github.otahelper.core.Backup
import io.github.otahelper.core.BootImageState
import io.github.otahelper.core.EngineStatus
import io.github.otahelper.core.OtaController
import io.github.otahelper.core.OtaStatus
import io.github.otahelper.core.Phase
import io.github.otahelper.core.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * In-memory [OtaController] for `@Preview` and for walking through the UI without root. With a [scope], a
 * simulated install starts after [prepare] (progress, then auto patch when enabled) and [reboot] lands on a
 * new build, so the whole flow can be exercised on any device. Result and log text comes from [text] (string
 * resources in demo mode), so screenshots read in the device language.
 */
internal class PreviewOtaController(
    initial: OtaStatus = sampleStatus(Phase.IDLE),
    backups: List<Backup> = sampleBackups(),
    private val scope: CoroutineScope? = null,
    private val text: (Int) -> String = { "" },
) : OtaController {
    private val mutableStatus = MutableStateFlow(initial)
    private val mutableSettings = MutableStateFlow(Settings())
    private val mutableBackups = MutableStateFlow(backups)
    private val mutableBusy = MutableStateFlow(false)
    private val logLines = MutableStateFlow(listOf("${now()} ${text(R.string.demo_started)}"))
    private var install: Job? = null

    override val status: StateFlow<OtaStatus> = mutableStatus.asStateFlow()
    override val settings: StateFlow<Settings> = mutableSettings.asStateFlow()
    override val backups: StateFlow<List<Backup>> = mutableBackups.asStateFlow()
    override val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    override suspend fun refresh() {
        delay(600)
        mutableStatus.update { if (it.phase == Phase.LOADING) sampleStatus(Phase.IDLE) else it }
        log(text(R.string.demo_refreshed))
    }

    override suspend fun prepare(): ActionResult = act(text(R.string.demo_prepared)) {
        mutableStatus.update { it.copy(phase = Phase.PREPARED, bootImage = BootImageState.STOCK) }
        install = scope?.launch { simulateInstall() }
    }

    override suspend fun cancel(): ActionResult = act(text(R.string.demo_cancelled)) {
        install?.cancel()
        mutableStatus.update {
            it.copy(phase = Phase.IDLE, bootImage = BootImageState.PATCHED, engine = EngineStatus.IDLE, progress = null)
        }
    }

    override suspend fun patch(): ActionResult = act(text(R.string.demo_patched)) {
        mutableStatus.update { it.copy(phase = Phase.PATCHED) }
    }

    override suspend fun reboot(): ActionResult = act(text(R.string.demo_rebooted)) {
        mutableStatus.update {
            sampleStatus(Phase.IDLE).copy(version = NEXT_VERSION, slot = if (it.slot == "A") "B" else "A")
        }
        mutableBackups.update { listOf(backup(NEXT_VERSION, System.currentTimeMillis())) + it }
    }

    override suspend fun setAutoPatch(enabled: Boolean) {
        mutableSettings.update { it.copy(autoPatch = enabled) }
    }

    override suspend fun readLog(): List<String> {
        delay(300)
        return logLines.value
    }

    private suspend fun act(message: String, block: () -> Unit): ActionResult {
        mutableBusy.value = true
        try {
            delay(1_500)
            block()
            log(message)
        } finally {
            mutableBusy.value = false
        }
        return ActionResult(ok = true, message = message)
    }

    private suspend fun simulateInstall() {
        delay(4_000)
        val ticks = 50
        for (tick in 0..ticks) {
            val progress = tick.toFloat() / ticks
            val engine = when {
                progress < 0.7f -> EngineStatus.DOWNLOADING
                progress < 0.9f -> EngineStatus.VERIFYING
                else -> EngineStatus.FINALIZING
            }
            mutableStatus.update { it.copy(phase = Phase.INSTALLING, engine = engine, progress = progress) }
            delay(200)
        }
        mutableStatus.update {
            it.copy(phase = Phase.INSTALLED, engine = EngineStatus.UPDATED_NEED_REBOOT, progress = null)
        }
        log(text(R.string.demo_installed))
        if (mutableSettings.value.autoPatch) patch()
    }

    private fun log(line: String) {
        logLines.update { (it + "${now()} $line").takeLast(200) }
    }

    companion object {
        private const val VERSION = "OS4.0.5.0.XPACNXM"
        private const val NEXT_VERSION = "OS4.0.6.0.XPACNXM"
        private const val DAY_MS = 86_400_000L
        private const val NOW_MS = 1_795_000_000_000L

        /** A plausible [OtaStatus] for [phase], matching the device this app was built for. */
        fun sampleStatus(phase: Phase): OtaStatus = when (phase) {
            Phase.LOADING, Phase.NO_ROOT -> OtaStatus(phase)

            Phase.FAILED -> OtaStatus(
                phase = phase,
                version = VERSION,
                slot = "A",
                partition = "init_boot",
                rootManager = "KernelSU 3.3.1",
                error = "Verification after flashing failed. Don't restart; see the log.",
            )

            Phase.UNSUPPORTED -> OtaStatus(
                phase = phase,
                version = VERSION,
                slot = "A",
                partition = "init_boot",
                error = "KernelSU is not running in LKM mode.",
            )

            else -> OtaStatus(
                phase = phase,
                version = VERSION,
                slot = "A",
                partition = "init_boot",
                bootImage = when (phase) {
                    Phase.PREPARED, Phase.INSTALLING, Phase.INSTALLED -> BootImageState.STOCK
                    else -> BootImageState.PATCHED
                },
                engine = when (phase) {
                    Phase.INSTALLING -> EngineStatus.DOWNLOADING
                    Phase.INSTALLED, Phase.PATCHED -> EngineStatus.UPDATED_NEED_REBOOT
                    else -> EngineStatus.IDLE
                },
                progress = if (phase == Phase.INSTALLING) 0.42f else null,
                rootManager = "KernelSU 3.3.1",
            )
        }

        fun sampleBackups(): List<Backup> = listOf(
            backup(VERSION, NOW_MS),
            backup("OS4.0.3.0.XPACNXM", NOW_MS - 24 * DAY_MS),
            Backup(
                version = "OS3.0.12.0.XPACNXM",
                stockPath = "${Settings.DEFAULT_BACKUP_DIR}/OS3.0.12.0.XPACNXM/init_boot_stock.img",
                patchedPath = null,
                modifiedMillis = NOW_MS - 61 * DAY_MS,
            ),
        )

        /** Starts in [Phase.LOADING] and simulates the whole flow; for debug builds run without root. */
        fun demo(context: Context): PreviewOtaController {
            val app = context.applicationContext
            return PreviewOtaController(
                initial = OtaStatus(Phase.LOADING),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                text = app::getString,
            )
        }

        private fun backup(version: String, millis: Long) = Backup(
            version = version,
            stockPath = "${Settings.DEFAULT_BACKUP_DIR}/$version/init_boot_stock.img",
            patchedPath = "${Settings.DEFAULT_BACKUP_DIR}/$version/init_boot_ksu.img",
            modifiedMillis = millis,
        )

        private fun now(): String = LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
    }
}
