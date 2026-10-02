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

class HardwareProtectionSettingTest {

    @Test
    fun `a fresh store and an old snapshot both read as the defaults`() {
        val defaults = com.homecam.core.health.HealthConfig()
        assertEquals(defaults, HealthProtocol.configOf(com.homecam.proto.SettingsSnapshot.getDefaultInstance()))
        assertEquals(true, defaults.thermalAutoPause)
        assertEquals(true, defaults.batteryAutoPause)
        assertEquals(30, defaults.batteryPausePercent)
    }

    @Test
    fun `the setting round-trips and the threshold is clamped to 10 to 80`() {
        val rows = listOf(5 to 10, 10 to 10, 45 to 45, 80 to 80, 95 to 80, 0 to 30)
        for ((asked, expected) in rows) {
            val snapshot = com.homecam.proto.SettingsSnapshot.newBuilder()
                .setHardwareProtection(
                    com.homecam.proto.HardwareProtection.newBuilder()
                        .setThermalAutoPause(false).setBatteryAutoPause(true).setBatteryPausePercent(asked)
                )
                .build()
            val parsed = com.homecam.proto.SettingsSnapshot.parseFrom(snapshot.toByteArray())
            val config = HealthProtocol.configOf(parsed)
            assertEquals("asked $asked", expected, config.batteryPausePercent)
            assertEquals(false, config.thermalAutoPause)
            assertEquals(expected, HealthProtocol.protectionOf(config).batteryPausePercent)
        }
    }

    @Test
    fun `with thermal auto-pause off a critical reading still removes recording`() {
        val config = HealthProtocol.configOf(
            com.homecam.proto.SettingsSnapshot.newBuilder()
                .setHardwareProtection(
                    com.homecam.proto.HardwareProtection.newBuilder()
                        .setThermalAutoPause(false).setBatteryAutoPause(false).setBatteryPausePercent(30)
                ).build()
        )
        val policy = com.homecam.core.health.HealthPolicy(config)
        val state = policy.update(
            com.homecam.core.health.HealthSample(
                atMs = 0, batteryPercent = 80, chargeCounterUah = 0, plugged = true, batteryTempC = 51f,
                thermalStatus = com.homecam.core.health.ThermalStatus.CRITICAL, freeBytes = 50_000_000_000,
                floorBytes = 8_000_000_000, floorHeld = true, pinnedNearCeiling = false,
                storageProjectionDays = null,
            )
        )
        assertEquals(Cap.NO_RECORDING, state.cap)
    }

    @Test
    fun `the viewer only offers the control when the camera advertises it`() {
        val gates = com.homecam.core.rpc.ControlGating.gatesFor(
            Capabilities.newBuilder().setHardwareProtection(true).build()
        )
        assertEquals(true, gates.hardwareProtection)
        assertEquals(false, com.homecam.core.rpc.ControlGating.gatesFor(Capabilities.getDefaultInstance()).hardwareProtection)
    }
}
