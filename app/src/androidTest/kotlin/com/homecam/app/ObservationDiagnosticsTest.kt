package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CameraDiagnostics
import com.homecam.core.diagnostics.AudioListen
import com.homecam.core.diagnostics.PowerSavingReason
import com.homecam.core.health.Cap
import com.homecam.core.health.ThermalStatus
import com.homecam.proto.RecordingMode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Each diagnostics builder the observation log reads, with no service behind it.
 * Lines are shape only: a peer address goes in and does not come back out.
 */
@RunWith(AndroidJUnit4::class)
class ObservationDiagnosticsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir by lazy { File(context.cacheDir, "observation-diagnostics-test") }
    private val events get() = File(dir, "camera.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject)

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun diagnostics() = CameraDiagnostics(context, dir)

    private fun only(event: String): JSONObject = events.single { it.getString("event") == event }

    @Test
    fun throttleRecordsTheStepAndTheReadingOnEngageAndRelease() {
        val diagnostics = diagnostics()
        diagnostics.throttle("fps", engaged = true, batteryTempC = 36.5f, thermalStatus = ThermalStatus.MODERATE)
        diagnostics.throttle("screen", engaged = false, batteryTempC = 34f, thermalStatus = null)

        val lines = events.filter { it.getString("event") == "throttle" }
        assertEquals("fps", lines[0].getString("step"))
        assertEquals("engaged", lines[0].getString("state"))
        assertEquals(36.5, lines[0].getDouble("battery_temp_c"), 0.01)
        assertEquals("moderate", lines[0].getString("thermal_status"))
        assertEquals("screen", lines[1].getString("step"))
        assertEquals("released", lines[1].getString("state"))
        assertEquals("unknown", lines[1].getString("thermal_status"))
    }

    @Test
    fun powerSavingRecordsStateAndReason() {
        diagnostics().powerSaving(engaged = true, reason = PowerSavingReason.IDLE)
        val line = only("power_saving")
        assertEquals("engaged", line.getString("state"))
        assertEquals("idle", line.getString("reason"))
    }

    @Test
    fun audioListenRecordsTheFeedAndWhyItStopped() {
        diagnostics().audioListen(AudioListen.TAP, started = false, reason = AudioListen.MIC_OFF)
        val line = only("audio_listen")
        assertEquals("tap", line.getString("feed"))
        assertEquals("stop", line.getString("state"))
        assertEquals("mic_off", line.getString("reason"))
    }

    @Test
    fun tlsRecordsProtocolAndPeerClassAndDropsTheAddress() {
        val diagnostics = diagnostics()
        val tailnet = "100.77.1.5"
        diagnostics.tls(protocol = "TLSv1.3", peerAddress = tailnet, accepted = true, reason = null)
        diagnostics.tls(protocol = "TLSv1.2", peerAddress = "192.168.1.9", accepted = false, reason = "limit")

        val lines = events.filter { it.getString("event") == "tls" }
        assertEquals("TLSv1.3", lines[0].getString("protocol"))
        assertEquals("tailnet", lines[0].getString("peer"))
        assertEquals("accepted", lines[0].getString("outcome"))
        assertFalse(lines[0].has("reason"))
        assertEquals("TLSv1.2", lines[1].getString("protocol"))
        assertEquals("lan", lines[1].getString("peer"))
        assertEquals("refused", lines[1].getString("outcome"))
        assertEquals("limit", lines[1].getString("reason"))
        assertFalse(lines[1].has("suppressed"))
        assertFalse(events.joinToString("\n").contains(tailnet))
        assertFalse(events.joinToString("\n").contains("192.168.1.9"))
    }

    @Test
    fun recordingTimerSetRecordsHowThePeerConnectedAndDropsTheAddress() {
        val address = "100.64.5.5"
        diagnostics().recordingTimerSet(
            mode = RecordingMode.CONTINUOUS,
            revertTo = RecordingMode.DETECTION,
            dueAtMs = 90_000,
            peerAddress = address,
        )
        val line = only("recording_timer_set")
        assertEquals("CONTINUOUS", line.getString("mode"))
        assertEquals("DETECTION", line.getString("revert_to"))
        assertEquals(90_000L, line.getLong("due_at_ms"))
        assertEquals("tailnet", line.getString("via"))
        assertFalse(line.toString().contains(address))
    }

    @Test
    fun spotlightRecordsLumaAndDurationOnlyWhenItTurnsOff() {
        val diagnostics = diagnostics()
        diagnostics.spotlight(on = true, auto = true, luma = 12.5f, durationMs = 20_000)
        diagnostics.spotlight(on = false, auto = true, luma = 40f, durationMs = 20_000)

        val lines = events.filter { it.getString("event") == "spotlight" }
        assertEquals(true, lines[0].getBoolean("on"))
        assertEquals(true, lines[0].getBoolean("auto"))
        assertEquals(12.5, lines[0].getDouble("luma"), 0.01)
        assertFalse(lines[0].has("duration_ms"))
        assertEquals(false, lines[1].getBoolean("on"))
        assertEquals(true, lines[1].getBoolean("auto"))
        assertEquals(20_000L, lines[1].getLong("duration_ms"))
    }

    @Test
    fun segmentCarriesTheCapAndTheEffectiveMode() {
        diagnostics().segment(
            id = "clip-1",
            durationMs = 8_000,
            sizeBytes = 100,
            gapMs = 0,
            cap = Cap.NO_CONTINUOUS,
            effectiveMode = RecordingMode.DETECTION,
        )
        val line = only("segment")
        assertEquals("no_continuous", line.getString("cap"))
        assertEquals("DETECTION", line.getString("effective_mode"))
        assertEquals(0L, line.getLong("gap_ms"))
    }

    @Test
    fun aHealthAlertCarriesItsLabelAndAnotherKindDoesNot() {
        val diagnostics = diagnostics()
        diagnostics.alert(kind = "health", recipients = 1, delivered = 1, healthLabel = "health:paused:thermal")
        diagnostics.alert(kind = "health", recipients = 1, delivered = 1, healthLabel = "health:resumed")
        diagnostics.alert(kind = "health", recipients = 1, delivered = 1, healthLabel = "health:storage-critical")
        diagnostics.alert(kind = "detection", recipients = 1, delivered = 1, healthLabel = "person")

        val lines = events.filter { it.getString("event") == "alert" }
        assertEquals("paused:thermal", lines[0].getString("label"))
        assertEquals("resumed", lines[1].getString("label"))
        assertEquals("storage-critical", lines[2].getString("label"))
        assertFalse(lines[3].has("label"))
    }
}
