package io.github.otahelper

import android.app.Application
import io.github.otahelper.core.EventLog
import io.github.otahelper.core.FileEventLog
import io.github.otahelper.core.OtaController
import io.github.otahelper.core.OtaEngine
import io.github.otahelper.core.RootShell
import io.github.otahelper.system.AndroidPlatform
import io.github.otahelper.system.Notifications
import io.github.otahelper.system.PrefsStateStore
import kotlinx.coroutines.launch
import java.io.File

class OtaApp : Application() {
    lateinit var log: EventLog
        private set

    lateinit var engine: OtaEngine
        private set

    /** The UI's entry point to the engine. */
    val controller: OtaController get() = engine

    override fun onCreate() {
        super.onCreate()
        // Device-protected storage: readable by the boot receiver regardless of lock state.
        val storage = createDeviceProtectedStorageContext()
        log = FileEventLog(File(storage.filesDir, LOG_FILE))
        engine = OtaEngine(
            shell = RootShell(log),
            store = PrefsStateStore(storage.getSharedPreferences(PrefsStateStore.FILE_NAME, MODE_PRIVATE)),
            log = log,
            platform = AndroidPlatform(this, log),
        )
        Notifications.createChannels(this)
        log.log("app start ${BuildConfig.VERSION_NAME}")
        engine.scope.launch { engine.refresh() }
    }

    private companion object {
        const val LOG_FILE = "ota.log"
    }
}
