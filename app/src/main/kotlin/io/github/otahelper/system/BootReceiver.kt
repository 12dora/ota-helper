package io.github.otahelper.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.otahelper.OtaApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * After a reboot: a refresh finishes a patched update (promotes the pending backup, notifies
 * "updated to X") and restarts the watcher if the device is still waiting for the update.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as OtaApp
        val engine = app.engine
        app.log.log("boot completed")
        val pending = goAsync()
        // The refresh itself is not cancellable once started; only the wait for it is bounded.
        val work = engine.scope.launch { engine.refresh() }
        engine.scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) { work.join() } ?: app.log.log("boot refresh still running")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 50_000L
    }
}
