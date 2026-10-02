package com.homecam.core

import com.homecam.core.health.HealthReadout
import com.homecam.proto.Capabilities
import com.homecam.proto.RecordingMode
import com.homecam.proto.RuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthReadoutTest {
    private val at: (Long) -> String = { "t$it" }

    private fun status() = RuntimeStatus.newBuilder()
        .setBatteryPercent(80f).setBatteryTempC(33.4f).setFreeBytes(73_000_000_000)
        .setRequestedRecordingMode(RecordingMode.CONTINUOUS)
        .setEffectiveRecordingMode(RecordingMode.CONTINUOUS)

    @Test
    fun `an unpaused camera reads as the mode that was asked`() {
        val readout = HealthReadout.recording(status().build(), at)
        assertEquals("Continuous", readout.title)
        assertEquals(emptyList<String>(), readout.details)
    }

    @Test
    fun `a paused camera says what is in force, why, since when, and that it resumes`() {
        val paused = status()
            .setCap(RuntimeStatus.Cap.CAP_NO_CONTINUOUS)
            .setCapReason(RuntimeStatus.CapReason.REASON_THERMAL)
            .setCapSinceMs(1402)
            .setEffectiveRecordingMode(RecordingMode.DETECTION)
            .build()
        val readout = HealthReadout.recording(paused, at)
        assertEquals("Continuous", readout.title)
        assertEquals(
            listOf("Paused: the phone is too hot, recording detections only since t1402. Resumes automatically."),
            readout.details,
        )
    }

    @Test
    fun `a cap that changes nothing is not reported as a pause`() {
        val detection = status()
            .setRequestedRecordingMode(RecordingMode.DETECTION)
            .setEffectiveRecordingMode(RecordingMode.DETECTION)
            .setCap(RuntimeStatus.Cap.CAP_NO_CONTINUOUS)
            .setCapReason(RuntimeStatus.CapReason.REASON_BATTERY)
            .build()
        assertEquals(emptyList<String>(), HealthReadout.recording(detection, at).details)
    }

    @Test
    fun `a timed mode names when it reverts`() {
        val timed = status().setRecordingRevertAtMs(1500).setRecordingRevertTo(RecordingMode.DETECTION).build()
        assertEquals(listOf("Reverts to Detection at t1500."), HealthReadout.recording(timed, at).details)
    }

    @Test
    fun `the card omits what the camera cannot report`() {
        val old = RuntimeStatus.newBuilder().setBatteryPercent(-1f).build()
        val card = HealthReadout.card(old, null, 30)
        assertFalse(card.any { it.label == "Battery" })
        assertFalse(card.any { it.label == "Storage" })
        assertFalse(card.any { it.label == "Protection" })
        assertEquals("0.0 °C", card.single { it.label == "Heat" }.value)
    }

    @Test
    fun `heat uses words only where the platform reports a thermal status`() {
        val withStatus = status().setThermalStatus(RuntimeStatus.Thermal.THERMAL_MODERATE).build()
        val caps = Capabilities.newBuilder().setThermalStatusSupported(true).build()
        assertEquals("Warm, 33.4 °C", HealthReadout.card(withStatus, caps, 30).single { it.label == "Heat" }.value)
        val without = Capabilities.newBuilder().setThermalStatusSupported(false).build()
        assertEquals("33.4 °C", HealthReadout.card(withStatus, without, 30).single { it.label == "Heat" }.value)
    }

    @Test
    fun `draining shows the rate and the time to the pause threshold`() {
        val draining = status().setBatteryPercent(60f).setDrainPercentPerHour(10f).build()
        assertEquals(
            "60%, draining 10.0%/h, 3.0 h to 30%",
            HealthReadout.card(draining, null, 30).single { it.label == "Battery" }.value,
        )
        val charging = status().setCharging(true).build()
        assertEquals("80%, charging", HealthReadout.card(charging, null, 30).single { it.label == "Battery" }.value)
        val losing = status().setCharging(true).setDrainPercentPerHour(3f).build()
        assertTrue(HealthReadout.card(losing, null, 30).single { it.label == "Battery" }.value.contains("charger losing"))
    }

    @Test
    fun `a held cap and a missed floor both show`() {
        val capped = status()
            .setCap(RuntimeStatus.Cap.CAP_NO_RECORDING).setCapReason(RuntimeStatus.CapReason.REASON_STORAGE)
            .setFloorBytes(8_000_000_000).setFloorHeld(false).build()
        val card = HealthReadout.card(capped, null, 30)
        assertEquals("73 GB free, below the floor", card.single { it.label == "Storage" }.value)
        assertEquals("Paused: the phone is nearly out of storage", card.single { it.label == "Protection" }.value)
    }
}
