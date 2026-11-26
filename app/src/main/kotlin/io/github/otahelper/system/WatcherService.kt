package io.github.otahelper.system

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import io.github.otahelper.OtaApp
import io.github.otahelper.core.EngineFollower
import io.github.otahelper.core.EngineStatusParser
import io.github.otahelper.core.OtaEngine
import io.github.otahelper.core.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that follows update_engine while the user installs the update. It feeds every
 * status line to [OtaEngine.onEngineUpdate] (which runs the automatic patch), mirrors the phase in an
 * ongoing notification, and stops once the flow no longer needs watching.
 */
class WatcherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as OtaApp).engine
        ServiceCompat.startForeground(
            this,
            Notifications.ID_WATCHER,
            Notifications.watcher(this, engine.status.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (!started) {
            started = true
            scope.launch { render(engine) }
            scope.launch { follow(engine) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun render(engine: OtaEngine) {
        combine(engine.status, engine.busy) { status, busy -> status to busy }.collect { (status, busy) ->
            if (status.phase in DONE_PHASES && !busy) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                Notifications.updateWatcher(this, Notifications.watcher(this, status))
            }
        }
    }

    /** Runs `update_engine_client --follow` again whenever it exits, backing off when it exits right away. */
    private suspend fun follow(engine: OtaEngine) {
        val app = application as OtaApp
        val follower = EngineFollower(app.log)
        var quickExits = 0
        while (currentCoroutineContext().isActive) {
            val startedAt = SystemClock.elapsedRealtime()
            val code = follower.follow { line -> EngineStatusParser.parseLine(line)?.let(engine::onEngineUpdate) }
            val quick = SystemClock.elapsedRealtime() - startedAt < QUICK_EXIT_MS
            quickExits = if (quick) quickExits + 1 else 0
            app.log.log("watcher: follow exited ($code)")
            delay(if (quick) (BACKOFF_STEP_MS * quickExits).coerceAtMost(BACKOFF_MAX_MS) else RESTART_DELAY_MS)
        }
    }

    private companion object {
        val DONE_PHASES = setOf(Phase.IDLE, Phase.PATCHED, Phase.NO_ROOT, Phase.UNSUPPORTED, Phase.FAILED)
        const val QUICK_EXIT_MS = 10_000L
        const val RESTART_DELAY_MS = 1_000L
        const val BACKOFF_STEP_MS = 5_000L
        const val BACKOFF_MAX_MS = 60_000L
    }
}
