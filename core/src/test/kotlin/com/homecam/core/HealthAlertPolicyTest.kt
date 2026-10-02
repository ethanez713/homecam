package com.homecam.core

import com.homecam.core.health.Axis
import com.homecam.core.health.AxisState
import com.homecam.core.health.Cap
import com.homecam.core.health.HealthAlertLabels
import com.homecam.core.health.HealthAlertPolicy
import com.homecam.core.health.HealthState
import com.homecam.core.health.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthAlertPolicyTest {
    private val minute = 60_000L

    private fun state(
        thermal: Level = Level.OK,
        storage: Level = Level.OK,
        battery: Level = Level.OK,
        cap: Cap = Cap.NONE,
        reason: Axis? = null,
    ) = HealthState(
        AxisState(thermal, 0), AxisState(storage, 0), AxisState(battery, 0), cap, reason, 0, null,
    )

    private val ok = state()
    private val hot = state(thermal = Level.PAUSE, cap = Cap.NO_CONTINUOUS, reason = Axis.THERMAL)

    @Test
    fun `a pause and its resume each send one alert naming the cause`() {
        val policy = HealthAlertPolicy()
        assertEquals(listOf(HealthAlertLabels.paused(Axis.THERMAL)), policy.onChange(ok, hot, 0).map { it.label })
        assertEquals(listOf(HealthAlertLabels.RESUMED), policy.onChange(hot, ok, 20 * minute).map { it.label })
    }

    @Test
    fun `pause then release then pause within thirty minutes sends two alerts, not three`() {
        val policy = HealthAlertPolicy()
        val sent = listOf(
            policy.onChange(ok, hot, 0),
            policy.onChange(hot, ok, 12 * minute),
            policy.onChange(ok, hot, 14 * minute),
            policy.onChange(hot, ok, 26 * minute),
        ).flatten()
        assertEquals(listOf(HealthAlertLabels.paused(Axis.THERMAL), HealthAlertLabels.RESUMED), sent.map { it.label })
    }

    @Test
    fun `a pause after the thirty minutes have passed is announced again`() {
        val policy = HealthAlertPolicy()
        policy.onChange(ok, hot, 0)
        policy.onChange(hot, ok, 12 * minute)
        assertEquals(1, policy.onChange(ok, hot, 31 * minute).size)
    }

    @Test
    fun `axes are limited independently`() {
        val policy = HealthAlertPolicy()
        val drained = state(battery = Level.PAUSE, cap = Cap.NO_CONTINUOUS, reason = Axis.BATTERY)
        val sent = listOf(
            policy.onChange(ok, hot, 0),
            policy.onChange(hot, ok, 5 * minute),
            policy.onChange(ok, drained, 6 * minute),
        ).flatten()
        assertEquals(
            listOf(HealthAlertLabels.paused(Axis.THERMAL), HealthAlertLabels.RESUMED, HealthAlertLabels.paused(Axis.BATTERY)),
            sent.map { it.label },
        )
    }

    @Test
    fun `a warning alone sends nothing`() {
        val policy = HealthAlertPolicy()
        assertEquals(emptyList<Any>(), policy.onChange(ok, state(thermal = Level.WARN), 0))
    }

    @Test
    fun `storage critical sends its own label once, not a pause as well`() {
        val policy = HealthAlertPolicy()
        val critical = state(storage = Level.CRITICAL, cap = Cap.NO_RECORDING, reason = Axis.STORAGE)
        assertEquals(listOf(HealthAlertLabels.STORAGE_CRITICAL), policy.onChange(ok, critical, 0).map { it.label })
    }

    @Test
    fun `a cap that only eases from no-recording to no-continuous sends nothing`() {
        val policy = HealthAlertPolicy()
        val critical = state(thermal = Level.CRITICAL, cap = Cap.NO_RECORDING, reason = Axis.THERMAL)
        policy.onChange(ok, critical, 0)
        assertEquals(emptyList<Any>(), policy.onChange(critical, hot, 15 * minute))
    }

    @Test
    fun `labels read as sentences and unknown labels read as nothing`() {
        assertEquals("Recording is paused: the phone is too hot.", HealthAlertLabels.describe("health:paused:thermal"))
        assertEquals("Continuous recording is back.", HealthAlertLabels.describe(HealthAlertLabels.RESUMED))
        assertNull(HealthAlertLabels.describe("person"))
        assertNull(HealthAlertLabels.describe("health:paused:sunspots"))
    }
}
