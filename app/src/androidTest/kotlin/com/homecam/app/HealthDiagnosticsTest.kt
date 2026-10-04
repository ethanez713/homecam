package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CameraDiagnostics
import com.homecam.core.health.Cap
import com.homecam.core.health.HealthPolicy
import com.homecam.core.health.HealthSample
import com.homecam.core.health.Level
import com.homecam.core.health.ThermalStatus
import com.homecam.proto.RecordingMode
import com.homecam.proto.RuntimeStatus
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HealthDiagnosticsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir by lazy { File(context.cacheDir, "health-diagnostics-test") }
    private val events get() = File(dir, "camera.jsonl").readLines().map(::JSONObject)

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun severeSample() = HealthSample(
        atMs = 1_000, batteryPercent = 80, chargeCounterUah = 0, plugged = true, batteryTempC = 44f,
        thermalStatus = ThermalStatus.SEVERE, freeBytes = 50_000_000_000, floorBytes = 8_000_000_000,
        floorHeld = true, pinnedNearCeiling = false, storageProjectionDays = null,
    )

    @Test
    fun aLevelChangeNamesTheAxisAndTheReadingThatCausedIt() {
        val diagnostics = CameraDiagnostics(context, dir)
        val state = HealthPolicy().update(severeSample())
        diagnostics.healthState(
            com.homecam.core.health.Axis.THERMAL, Level.OK, Level.PAUSE, severeSample(), state, batteryPausePct = 30,
        )

        val line = events.single { it.getString("event") == "health_state" }
        assertEquals("thermal", line.getString("axis"))
        assertEquals("ok", line.getString("from"))
        assertEquals("pause", line.getString("to"))
        assertEquals("severe", line.getString("thermal_status"))
        assertEquals(44.0, line.getDouble("battery_temp_c"), 0.01)
        assertEquals(30, line.getInt("battery_pause_pct"))
        assertEquals(0L, line.getLong("charge_counter_uah"))
    }

    @Test
    fun aCapChangeRecordsRequestedAndEffectiveModes() {
        val diagnostics = CameraDiagnostics(context, dir)
        val state = HealthPolicy().update(severeSample())
        diagnostics.recordingCap(state, RecordingMode.CONTINUOUS, RecordingMode.DETECTION, batteryTempC = 44f)

        val line = events.single { it.getString("event") == "recording_cap" }
        assertEquals(Cap.NO_CONTINUOUS.name.lowercase(), line.getString("cap"))
        assertEquals("thermal", line.getString("reason"))
        assertEquals("CONTINUOUS", line.getString("requested_mode"))
        assertEquals("DETECTION", line.getString("effective_mode"))
        assertEquals(44.0, line.getDouble("battery_temp_c"), 0.01)
    }

    @Test
    fun theHeartbeatCarriesTheHealthFieldsAndOmitsAnAbsentHeadroom() {
        val diagnostics = CameraDiagnostics(context, dir)
        val status = RuntimeStatus.newBuilder()
            .setThermalStatus(RuntimeStatus.Thermal.THERMAL_MODERATE)
            .setThermalHeadroom(Float.NaN)
            .setDrainPercentPerHour(3.5f)
            .setCap(RuntimeStatus.Cap.CAP_NO_CONTINUOUS)
            .setEffectiveRecordingMode(RecordingMode.DETECTION)
            .build()
        diagnostics.heartbeat(
            status, recording = false, mode = "CONTINUOUS", batteryPausePct = 30, chargeCounterUah = 1_500_000,
        )

        val line = events.single { it.getString("event") == "heartbeat" }
        assertEquals("moderate", line.getString("thermal_status"))
        assertFalse(line.has("thermal_headroom"))
        assertEquals(3.5, line.getDouble("drain_pct_per_h"), 0.01)
        assertEquals("no_continuous", line.getString("cap"))
        assertTrue(line.getString("effective_mode") == "DETECTION")
        assertEquals(30, line.getInt("battery_pause_pct"))
        assertEquals(1_500_000L, line.getLong("charge_counter_uah"))
    }
}
