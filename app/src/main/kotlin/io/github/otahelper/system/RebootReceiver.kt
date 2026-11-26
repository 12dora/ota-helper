package io.github.otahelper.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.otahelper.OtaApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** "Restart now" action of the patched notification. The engine re-checks that the new slot is patched. */
class RebootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REBOOT) return
        val app = context.applicationContext as OtaApp
        val engine = app.engine
        val pending = goAsync()
        val work = engine.scope.launch {
            val result = engine.reboot()
            app.log.log("reboot from notification: ok=${result.ok}")
            // Keep the "patched" notification (with its action) unless the reboot is underway.
            if (result.ok) {
                Notifications.cancelPatched(app)
            } else {
                Notifications.postRebootFailed(app, result.message)
            }
        }
        engine.scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) { work.join() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REBOOT = "io.github.otahelper.action.REBOOT"

        /** Below the broadcast ANR limit; a reboot that takes longer still runs on in the engine scope. */
        private const val TIMEOUT_MS = 9_000L
    }
}
