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
        Notifications.cancelPatched(context)
        val pending = goAsync()
        val work = engine.scope.launch {
            val result = engine.reboot()
            app.log.log("reboot from notification: ok=${result.ok}")
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
        private const val TIMEOUT_MS = 50_000L
    }
}
