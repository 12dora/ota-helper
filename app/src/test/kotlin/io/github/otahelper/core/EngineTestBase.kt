package io.github.otahelper.core

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.Executors

const val WAIT_MS = 5_000L
const val POLL_MS = 10L
const val SETTLE_MS = 200L

/** An [OtaEngine] over a [FakeDevice] running patched V1 on slot A, with fake store, platform and log. */
open class EngineTestBase {
    protected val device = FakeDevice().apply { installPatchedV1() }
    protected val store = MemoryStore()
    protected val platform = FakePlatform()
    protected val log = MemoryLog()
    protected val executor = Executors.newSingleThreadExecutor()
    protected val engine = OtaEngine(device.shell, store, log, platform, executor.asCoroutineDispatcher())

    protected val root = Settings.DEFAULT_BACKUP_DIR
    protected val partA = device.part("_a")
    protected val partB = device.part("_b")

    @AfterEach
    fun tearDown() {
        engine.scope.cancel()
        executor.shutdownNow()
    }

    /** The user installs build V2 in the system updater; update_engine wrote stock V2 to slot B. */
    protected fun installUpdate() {
        device.write(partB, "stock:V2")
        device.engine("UPDATED_NEED_REBOOT", 6, "1")
    }

    protected suspend fun waitFor(condition: () -> Boolean) = withTimeout(WAIT_MS) {
        while (!condition()) delay(POLL_MS)
    }

    protected fun flashes() = device.shell.count("ksud boot-(restore|patch)")
}
