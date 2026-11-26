package io.github.otahelper.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineStatusParserTest {
    @Test
    fun `parses idle line from device`() {
        val update = EngineStatusParser.parseLine(
            "[INFO:update_engine_client_android.cc(98)] onStatusUpdate(UPDATE_STATUS_IDLE (0), 0)",
        )
        assertEquals(EngineUpdate(EngineStatus.IDLE, 0f), update)
    }

    @Test
    fun `parses downloading progress`() {
        val update = EngineStatusParser.parseLine(
            "[INFO:update_engine_client_android.cc(98)] onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 0.42)",
        )
        assertEquals(EngineUpdate(EngineStatus.DOWNLOADING, 0.42f), update)
    }

    @Test
    fun `parses every status code`() {
        val expected = listOf(
            "IDLE" to EngineStatus.IDLE,
            "CHECKING_FOR_UPDATE" to EngineStatus.CHECKING_FOR_UPDATE,
            "UPDATE_AVAILABLE" to EngineStatus.UPDATE_AVAILABLE,
            "DOWNLOADING" to EngineStatus.DOWNLOADING,
            "VERIFYING" to EngineStatus.VERIFYING,
            "FINALIZING" to EngineStatus.FINALIZING,
            "UPDATED_NEED_REBOOT" to EngineStatus.UPDATED_NEED_REBOOT,
            "REPORTING_ERROR_EVENT" to EngineStatus.REPORTING_ERROR_EVENT,
            "ATTEMPTING_ROLLBACK" to EngineStatus.ATTEMPTING_ROLLBACK,
            "DISABLED" to EngineStatus.DISABLED,
            "NEED_PERMISSION_TO_UPDATE" to EngineStatus.NEED_PERMISSION_TO_UPDATE,
            "CLEANUP_PREVIOUS_UPDATE" to EngineStatus.CLEANUP_PREVIOUS_UPDATE,
        )
        expected.forEachIndexed { code, (name, status) ->
            val line = "onStatusUpdate(UPDATE_STATUS_$name ($code), 1)"
            assertEquals(EngineUpdate(status, 1f), EngineStatusParser.parseLine(line), line)
        }
    }

    @Test
    fun `code wins over name and unknown codes fall back to the name`() {
        assertEquals(
            EngineStatus.VERIFYING,
            EngineStatusParser.parseLine("onStatusUpdate(UPDATE_STATUS_RENAMED (4), 0.5)")?.status,
        )
        assertEquals(
            EngineStatus.FINALIZING,
            EngineStatusParser.parseLine("onStatusUpdate(UPDATE_STATUS_FINALIZING (99), 0.5)")?.status,
        )
        assertEquals(
            EngineStatus.UNKNOWN,
            EngineStatusParser.parseLine("onStatusUpdate(UPDATE_STATUS_NEW_THING (42), 0)")?.status,
        )
    }

    @Test
    fun `progress is clamped and tolerates odd numbers`() {
        assertEquals(1f, EngineStatusParser.parseLine("onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 1.5)")?.progress)
        assertEquals(0f, EngineStatusParser.parseLine("onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), nan)")?.progress)
        val tiny = EngineStatusParser.parseLine("onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 1e-05)")?.progress
        assertNotNull(tiny)
        assertTrue(tiny > 0f && tiny < 0.001f)
    }

    @Test
    fun `ignores lines that are not status updates`() {
        assertNull(
            EngineStatusParser.parseLine(
                "[INFO:update_engine_client_android.cc(208)] Waiting for update_engine service...",
            ),
        )
        assertNull(EngineStatusParser.parseLine("[INFO:update_engine_client_android.cc(390)] Command took 3984 ms"))
        assertNull(EngineStatusParser.parseLine(""))
    }

    @Test
    fun `parseLast takes the final status of a follow session`() {
        val output = """
            [INFO:update_engine_client_android.cc(208)] Waiting for update_engine service...
            [INFO:update_engine_client_android.cc(222)] Successfully connected to update_engine service.
            [INFO:update_engine_client_android.cc(98)] onStatusUpdate(UPDATE_STATUS_FINALIZING (5), 0.99)
            [INFO:update_engine_client_android.cc(98)] onStatusUpdate(UPDATE_STATUS_UPDATED_NEED_REBOOT (6), 1)
            [INFO:update_engine_client_android.cc(390)] Command took 3984 ms
        """.trimIndent()
        assertEquals(EngineUpdate(EngineStatus.UPDATED_NEED_REBOOT, 1f), EngineStatusParser.parseLast(output))
        assertNull(EngineStatusParser.parseLast("Waiting for update_engine service..."))
    }

    @Test
    fun `overall progress is monotonic across install stages`() {
        val stages = listOf(
            EngineUpdate(EngineStatus.CLEANUP_PREVIOUS_UPDATE, 0.7f),
            EngineUpdate(EngineStatus.DOWNLOADING, 0f),
            EngineUpdate(EngineStatus.DOWNLOADING, 1f),
            EngineUpdate(EngineStatus.VERIFYING, 0.5f),
            EngineUpdate(EngineStatus.FINALIZING, 0f),
            EngineUpdate(EngineStatus.FINALIZING, 1f),
        ).map { assertNotNull(EngineStatusParser.overallProgress(it)) }
        assertEquals(stages.sorted(), stages)
        assertEquals(0f, stages.first())
        assertEquals(1f, stages.last(), 0.0001f)
        assertNull(EngineStatusParser.overallProgress(EngineUpdate(EngineStatus.IDLE, 0f)))
        assertNull(EngineStatusParser.overallProgress(EngineUpdate(EngineStatus.UPDATED_NEED_REBOOT, 1f)))
    }

    @Test
    fun `quotes shell arguments`() {
        assertEquals("'/sdcard/a b'", shQuote("/sdcard/a b"))
        assertEquals("'it'\\''s'", shQuote("it's"))
    }
}
