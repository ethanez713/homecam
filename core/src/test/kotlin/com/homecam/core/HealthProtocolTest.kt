package com.homecam.core

import com.homecam.core.health.Axis
import com.homecam.core.health.Cap
import com.homecam.core.health.HealthProtocol
import com.homecam.core.health.HealthProtocol.setHealth
import com.homecam.core.health.HealthState
import com.homecam.core.health.AxisState
import com.homecam.core.health.Level
import com.homecam.proto.Capabilities
import com.homecam.proto.RecordingMode
import com.homecam.proto.RuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HealthProtocolTest {

    @Test
    fun `the cap holds the requested mode down and never raises it`() {
        val rows = listOf(
            Triple(RecordingMode.CONTINUOUS, Cap.NONE, RecordingMode.CONTINUOUS),
            Triple(RecordingMode.CONTINUOUS, Cap.NO_CONTINUOUS, RecordingMode.DETECTION),
            Triple(RecordingMode.CONTINUOUS, Cap.NO_RECORDING, RecordingMode.OFF),
            Triple(RecordingMode.DETECTION, Cap.NO_CONTINUOUS, RecordingMode.DETECTION),
            Triple(RecordingMode.DETECTION, Cap.NO_RECORDING, RecordingMode.OFF),
            Triple(RecordingMode.OFF, Cap.NO_CONTINUOUS, RecordingMode.OFF),
            Triple(RecordingMode.OFF, Cap.NONE, RecordingMode.OFF),
        )
        for ((requested, cap, expected) in rows) {
            assertEquals("$requested under $cap", expected, HealthProtocol.effectiveMode(requested, cap))
        }
    }

    @Test
    fun `the extended runtime status round-trips`() {
        val state = HealthState(
            thermal = AxisState(Level.PAUSE, 10),
            storage = AxisState(Level.OK, 0),
            battery = AxisState(Level.WARN, 5),
            cap = Cap.NO_CONTINUOUS,
            capReason = Axis.THERMAL,
            capSinceMs = 1_234,
            netPercentPerHour = -4.5f,
        )
        val status = RuntimeStatus.newBuilder()
            .setThermalStatus(RuntimeStatus.Thermal.THERMAL_SEVERE)
            .setThermalHeadroom(0.8f)
            .setBatteryHealth(2)
            .setPinnedBytes(7).setFloorBytes(8).setFloorHeld(true)
            .setHealth(state, RecordingMode.CONTINUOUS)
            .build()

        val parsed = RuntimeStatus.parseFrom(status.toByteArray())
        assertEquals(status, parsed)
        assertEquals(RuntimeStatus.Cap.CAP_NO_CONTINUOUS, parsed.cap)
        assertEquals(RuntimeStatus.CapReason.REASON_THERMAL, parsed.capReason)
        assertEquals(RuntimeStatus.Level.LEVEL_PAUSE, parsed.thermalLevel)
        assertEquals(RecordingMode.DETECTION, parsed.effectiveRecordingMode)
        assertEquals(4.5f, parsed.drainPercentPerHour, 0.001f)
    }

    @Test
    fun `an older camera's status reads as healthy and unprotected`() {
        val parsed = RuntimeStatus.parseFrom(RuntimeStatus.newBuilder().setBatteryPercent(50f).build().toByteArray())
        assertEquals(RuntimeStatus.Cap.CAP_NONE, parsed.cap)
        assertEquals(RuntimeStatus.Thermal.THERMAL_UNKNOWN, parsed.thermalStatus)
        assertFalse(Capabilities.getDefaultInstance().hardwareProtection)
    }
}
