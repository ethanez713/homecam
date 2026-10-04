package com.homecam.core

import com.homecam.core.health.Axis
import com.homecam.core.health.Cap
import com.homecam.core.health.HealthConfig
import com.homecam.core.health.HealthPolicy
import com.homecam.core.health.HealthSample
import com.homecam.core.health.HealthThresholds
import com.homecam.core.health.HealthProtocol
import com.homecam.core.health.HealthState
import com.homecam.core.health.Level
import com.homecam.core.health.ThermalStatus
import com.homecam.proto.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthPolicyTest {
    private val minute = 60_000L
    private val gb = 1_000_000_000L

    private fun sample(
        atMs: Long = 0,
        percent: Int = 80,
        counterUah: Long = 0,
        plugged: Boolean = true,
        tempC: Float = 30f,
        thermal: ThermalStatus? = ThermalStatus.NONE,
        free: Long = 50 * gb,
        floor: Long = 8 * gb,
        floorHeld: Boolean = true,
        pinnedNearCeiling: Boolean = false,
        projectionDays: Float? = null,
    ) = HealthSample(
        atMs, percent, counterUah, plugged, tempC, thermal, free, floor, floorHeld,
        pinnedNearCeiling, projectionDays,
    )

    private fun HealthPolicy.feed(vararg samples: HealthSample) = samples.map { update(it) }.last()

    private fun thermal(level: Level, cap: Cap, throttled: Boolean) = Triple(level, cap, throttled)

    private fun thermalOf(state: HealthState) = Triple(state.thermal.level, state.cap, state.throttled)

    @Test
    fun `thermal status maps to a level and a cap`() {
        val rows = listOf(
            ThermalStatus.NONE to thermal(Level.OK, Cap.NONE, throttled = false),
            ThermalStatus.LIGHT to thermal(Level.WARN, Cap.NONE, throttled = false),
            ThermalStatus.MODERATE to thermal(Level.THROTTLE, Cap.NONE, throttled = true),
            ThermalStatus.SEVERE to thermal(Level.PAUSE, Cap.NO_CONTINUOUS, throttled = true),
            ThermalStatus.CRITICAL to thermal(Level.CRITICAL, Cap.NO_RECORDING, throttled = true),
            ThermalStatus.EMERGENCY to thermal(Level.CRITICAL, Cap.NO_RECORDING, throttled = true),
            ThermalStatus.SHUTDOWN to thermal(Level.CRITICAL, Cap.NO_RECORDING, throttled = true),
        )
        for ((status, expected) in rows) {
            val state = HealthPolicy().update(sample(thermal = status))
            assertEquals("$status", expected, thermalOf(state))
        }
    }

    @Test
    fun `without a thermal status the battery temperature stands in`() {
        val rows = listOf(
            33.9f to thermal(Level.OK, Cap.NONE, throttled = false),
            34f to thermal(Level.WARN, Cap.NONE, throttled = false),
            35.9f to thermal(Level.WARN, Cap.NONE, throttled = false),
            36f to thermal(Level.THROTTLE, Cap.NONE, throttled = true),
            39.9f to thermal(Level.THROTTLE, Cap.NONE, throttled = true),
            40f to thermal(Level.PAUSE, Cap.NO_CONTINUOUS, throttled = true),
            49.9f to thermal(Level.PAUSE, Cap.NO_CONTINUOUS, throttled = true),
            50f to thermal(Level.CRITICAL, Cap.NO_RECORDING, throttled = true),
        )
        for ((temp, expected) in rows) {
            val state = HealthPolicy().update(sample(thermal = null, tempC = temp))
            assertEquals("$temp", expected, thermalOf(state))
        }
    }

    @Test
    fun `a reported none status does not suppress the temperature and the worse of the two wins`() {
        val rows = listOf(
            sample(thermal = ThermalStatus.NONE, tempC = 33f) to thermal(Level.OK, Cap.NONE, false),
            sample(thermal = ThermalStatus.NONE, tempC = 36f) to thermal(Level.THROTTLE, Cap.NONE, true),
            sample(thermal = ThermalStatus.NONE, tempC = 40f) to thermal(Level.PAUSE, Cap.NO_CONTINUOUS, true),
            sample(thermal = ThermalStatus.LIGHT, tempC = 36f) to thermal(Level.THROTTLE, Cap.NONE, true),
            sample(thermal = ThermalStatus.SEVERE, tempC = 30f) to thermal(Level.PAUSE, Cap.NO_CONTINUOUS, true),
            sample(thermal = ThermalStatus.MODERATE, tempC = 50f) to thermal(Level.CRITICAL, Cap.NO_RECORDING, true),
        )
        for ((reading, expected) in rows) {
            val state = HealthPolicy().update(reading)
            assertEquals("${reading.thermalStatus} @ ${reading.batteryTempC}", expected, thermalOf(state))
        }
    }

    @Test
    fun `pause is immediate but release waits out the dwell`() {
        val policy = HealthPolicy()
        assertEquals(Cap.NO_CONTINUOUS, policy.update(sample(0, thermal = ThermalStatus.SEVERE)).cap)

        val cooled = policy.update(sample(1 * minute, thermal = ThermalStatus.LIGHT))
        assertEquals(Cap.NO_CONTINUOUS, cooled.cap)
        assertEquals(Cap.NO_CONTINUOUS, policy.update(sample(10 * minute, thermal = ThermalStatus.LIGHT)).cap)
        assertEquals(Cap.NONE, policy.update(sample(11 * minute, thermal = ThermalStatus.LIGHT)).cap)
    }

    @Test
    fun `a reading that flaps across the threshold makes one transition`() {
        val policy = HealthPolicy()
        val caps = (0..40).map { i ->
            val status = if (i % 2 == 0) ThermalStatus.SEVERE else ThermalStatus.MODERATE
            policy.update(sample(i * minute, thermal = status)).cap
        }
        assertEquals(listOf(Cap.NO_CONTINUOUS), caps.distinct())
    }

    @Test
    fun `a dip below the pause release that returns restarts the dwell`() {
        val policy = HealthPolicy()
        policy.update(sample(0, tempC = 40f, thermal = ThermalStatus.NONE))
        policy.update(sample(1 * minute, tempC = 37f))
        policy.update(sample(8 * minute, tempC = 41f))
        val state = policy.update(sample(30 * minute, tempC = 39f))
        assertEquals(Level.PAUSE, state.thermal.level)
        assertEquals(Cap.NO_CONTINUOUS, state.cap)
    }

    @Test
    fun `a reading that flaps across a release point stays at the level`() {
        val throttle = HealthPolicy()
        val throttleLevels = (0..40).map { i ->
            val temp = if (i % 2 == 0) 36.5f else 35f
            throttle.update(sample(i * minute, tempC = temp, thermal = ThermalStatus.NONE)).thermal.level
        }
        assertEquals(listOf(Level.THROTTLE), throttleLevels.distinct())

        val pause = HealthPolicy()
        val caps = (0..40).map { i ->
            val temp = if (i % 2 == 0) 40.5f else 39f
            pause.update(sample(i * minute, tempC = temp, thermal = ThermalStatus.NONE)).cap
        }
        assertEquals(listOf(Cap.NO_CONTINUOUS), caps.distinct())
    }

    @Test
    fun `throttle and pause release only at the margin after the dwell`() {
        val policy = HealthPolicy()
        assertEquals(Level.THROTTLE, policy.update(sample(0, tempC = 36f, thermal = ThermalStatus.NONE)).thermal.level)
        assertEquals(Level.THROTTLE, policy.update(sample(1 * minute, tempC = 35f)).thermal.level)
        policy.update(sample(2 * minute, tempC = 34f))
        assertEquals(Level.THROTTLE, policy.update(sample(11 * minute, tempC = 34f)).thermal.level)
        val released = policy.update(sample(12 * minute, tempC = 34f))
        assertEquals(Level.WARN, released.thermal.level)
        assertFalse(released.throttled)
        assertEquals(Cap.NONE, released.cap)

        val pausing = HealthPolicy()
        assertEquals(Level.PAUSE, pausing.update(sample(0, tempC = 40f, thermal = ThermalStatus.NONE)).thermal.level)
        assertEquals(Level.PAUSE, pausing.update(sample(1 * minute, tempC = 39f)).thermal.level)
        pausing.update(sample(2 * minute, tempC = 38f))
        assertEquals(Cap.NO_CONTINUOUS, pausing.update(sample(11 * minute, tempC = 38f)).cap)
        val stepped = pausing.update(sample(12 * minute, tempC = 38f))
        assertEquals(Level.THROTTLE, stepped.thermal.level)
        assertEquals(Cap.NONE, stepped.cap)
        assertTrue(stepped.throttled)
    }

    @Test
    fun `critical steps down to pause and then to ok`() {
        val policy = HealthPolicy()
        policy.update(sample(0, thermal = ThermalStatus.CRITICAL))
        policy.update(sample(1 * minute, thermal = ThermalStatus.SEVERE))
        assertEquals(Level.PAUSE, policy.update(sample(12 * minute, thermal = ThermalStatus.SEVERE)).thermal.level)
        policy.update(sample(13 * minute, thermal = ThermalStatus.LIGHT))
        val warned = policy.update(sample(23 * minute, thermal = ThermalStatus.LIGHT))
        assertEquals(Level.WARN, warned.thermal.level)
        assertEquals(Cap.NONE, warned.cap)
        assertFalse(warned.throttled)
        policy.update(sample(24 * minute, thermal = ThermalStatus.NONE))
        assertEquals(Level.OK, policy.update(sample(34 * minute, thermal = ThermalStatus.NONE)).thermal.level)
    }

    @Test
    fun `throttling never changes the effective recording mode`() {
        val policy = HealthPolicy()
        val throttled = policy.update(sample(tempC = 36f, thermal = ThermalStatus.NONE))
        assertEquals(Level.THROTTLE, throttled.thermal.level)
        assertTrue(throttled.throttled)
        assertEquals(Cap.NONE, throttled.cap)
        for (mode in listOf(RecordingMode.CONTINUOUS, RecordingMode.DETECTION, RecordingMode.OFF)) {
            assertEquals(mode, HealthProtocol.effectiveMode(mode, throttled.cap))
        }
        val paused = policy.update(sample(atMs = 1, tempC = 30f, thermal = ThermalStatus.SEVERE))
        assertEquals(Cap.NO_CONTINUOUS, paused.cap)
        assertEquals(RecordingMode.DETECTION, HealthProtocol.effectiveMode(RecordingMode.CONTINUOUS, paused.cap))
    }

    @Test
    fun `thermal auto-pause off disables only the pause rung`() {
        val policy = HealthPolicy(HealthConfig(thermalAutoPause = false))
        val severe = policy.update(sample(0, thermal = ThermalStatus.SEVERE))
        assertEquals(Level.THROTTLE, severe.thermal.level)
        assertTrue(severe.throttled)
        assertEquals(Cap.NONE, severe.cap)
        val critical = policy.update(sample(1, thermal = ThermalStatus.CRITICAL))
        assertEquals(Level.CRITICAL, critical.thermal.level)
        assertEquals(Cap.NO_RECORDING, critical.cap)

        val hot = HealthPolicy(HealthConfig(thermalAutoPause = false))
            .update(sample(thermal = ThermalStatus.NONE, tempC = 42f))
        assertEquals(Level.THROTTLE, hot.thermal.level)
        assertTrue(hot.throttled)
        assertEquals(Cap.NONE, hot.cap)
    }

    @Test
    fun `unplugged battery rungs`() {
        val rows = listOf(
            80 to Level.OK, 41 to Level.OK, 40 to Level.WARN, 31 to Level.WARN,
            30 to Level.PAUSE, 10 to Level.PAUSE,
        )
        for ((percent, level) in rows) {
            val state = HealthPolicy().update(sample(percent = percent, plugged = false))
            assertEquals("$percent%", level, state.battery.level)
        }
    }

    @Test
    fun `the battery pause threshold is configurable and battery auto-pause can be off`() {
        val raised = HealthPolicy(HealthConfig(batteryPausePercent = 50))
        assertEquals(Level.PAUSE, raised.update(sample(percent = 45, plugged = false)).battery.level)

        val off = HealthPolicy(HealthConfig(batteryAutoPause = false))
        val state = off.update(sample(percent = 12, plugged = false))
        assertEquals(Cap.NONE, state.cap)
        assertEquals(Level.WARN, state.battery.level)
    }

    @Test
    fun `battery pause releases only ten points above the threshold`() {
        val policy = HealthPolicy()
        policy.update(sample(0, percent = 30, plugged = false))
        assertEquals(Level.PAUSE, policy.update(sample(1 * minute, percent = 31, plugged = false)).battery.level)
        assertEquals(Level.PAUSE, policy.update(sample(60 * minute, percent = 39, plugged = false)).battery.level)
    }

    @Test
    fun `battery quantisation gives no rate from under twenty minutes of percent samples`() {
        val policy = HealthPolicy()
        val state = policy.feed(
            sample(0, percent = 90, plugged = false),
            sample(5 * minute, percent = 89, plugged = false),
            sample(10 * minute, percent = 88, plugged = false),
            sample(15 * minute, percent = 87, plugged = false),
        )
        assertNull(state.netPercentPerHour)
        assertEquals(Level.OK, state.battery.level)
    }

    @Test
    fun `a charge counter gives a rate within five minutes and a fast drain pauses`() {
        val capacity = 5_000_000L
        val policy = HealthPolicy()
        var percent = 90.0
        val states = (0..6).map { i ->
            percent -= 0.9
            val counter = (capacity * percent / 100).toLong()
            policy.update(sample(i * minute, percent = percent.toInt(), counterUah = counter, plugged = false))
        }
        val last = states.last()
        assertNotNull(last.netPercentPerHour)
        assertTrue("rate was ${last.netPercentPerHour}", last.drainPercentPerHour in 50f..58f)
        assertEquals(Cap.NO_CONTINUOUS, last.cap)
        assertEquals(Axis.BATTERY, last.capReason)
    }

    @Test
    fun `a slow drain over thirty minutes does not pause`() {
        val policy = HealthPolicy()
        val states = (0..6).map { i ->
            policy.update(sample(i * 5 * minute, percent = 90 - i / 2, plugged = false))
        }
        assertEquals(Level.OK, states.last().battery.level)
        assertNotNull(states.last().netPercentPerHour)
    }

    @Test
    fun `the rate never spans a plug edge`() {
        val policy = HealthPolicy()
        policy.feed(
            sample(0, percent = 60, plugged = false),
            sample(10 * minute, percent = 50, plugged = false),
            sample(20 * minute, percent = 40, plugged = false),
            sample(25 * minute, percent = 40, plugged = true),
        )
        val afterEdge = policy.update(sample(30 * minute, percent = 41, plugged = true))
        assertNull(afterEdge.netPercentPerHour)
    }

    @Test
    fun `a charger that loses is a warning, and below half it pauses`() {
        fun run(startPercent: Int): Pair<Level, Cap> {
            val policy = HealthPolicy()
            val state = policy.feed(
                sample(0, percent = startPercent, plugged = true),
                sample(10 * minute, percent = startPercent, plugged = true),
                sample(20 * minute, percent = startPercent - 1, plugged = true),
                sample(30 * minute, percent = startPercent - 2, plugged = true),
            )
            return state.battery.level to state.cap
        }
        assertEquals(Level.WARN to Cap.NONE, run(80))
        assertEquals(Level.PAUSE to Cap.NO_CONTINUOUS, run(45))
    }

    @Test
    fun `a plugged phone that holds its charge is fine at any level`() {
        val policy = HealthPolicy()
        val state = policy.feed(
            sample(0, percent = 20, plugged = true),
            sample(10 * minute, percent = 20, plugged = true),
            sample(20 * minute, percent = 21, plugged = true),
            sample(30 * minute, percent = 21, plugged = true),
        )
        assertEquals(Level.OK, state.battery.level)
    }

    @Test
    fun `plugging in a paused phone releases only once the charge is rising`() {
        val policy = HealthPolicy()
        policy.update(sample(0, percent = 25, plugged = false))
        var last = policy.update(sample(1 * minute, percent = 25, plugged = true))
        assertEquals(Level.PAUSE, last.battery.level)
        for (i in 2..40) {
            last = policy.update(sample(i * minute, percent = 25 + i / 4, plugged = true))
        }
        assertEquals(Level.OK, last.battery.level)
    }

    @Test
    fun `storage rungs`() {
        data class Row(val name: String, val sample: HealthSample, val level: Level, val cap: Cap)
        val rows = listOf(
            Row("healthy", sample(), Level.OK, Cap.NONE),
            Row("pinned near ceiling", sample(pinnedNearCeiling = true), Level.WARN, Cap.NONE),
            Row("projection under a day", sample(projectionDays = 0.5f), Level.WARN, Cap.NONE),
            Row("floor not held", sample(floorHeld = false), Level.PAUSE, Cap.NO_CONTINUOUS),
            Row("under half the floor", sample(free = 3 * gb, floorHeld = false), Level.CRITICAL, Cap.NO_RECORDING),
        )
        for (row in rows) {
            val state = HealthPolicy().update(row.sample)
            assertEquals(row.name, row.level to row.cap, state.storage.level to state.cap)
        }
    }

    @Test
    fun `the cap is the worst axis and names it`() {
        val state = HealthPolicy().update(
            sample(
                percent = 20, plugged = false,
                thermal = ThermalStatus.CRITICAL,
                floorHeld = false,
            )
        )
        assertEquals(Cap.NO_RECORDING, state.cap)
        assertEquals(Axis.THERMAL, state.capReason)
        assertEquals(Level.PAUSE, state.battery.level)
        assertEquals(Level.PAUSE, state.storage.level)
    }

    @Test
    fun `capSince moves with the cap and not with its reason`() {
        val policy = HealthPolicy()
        policy.update(sample(1_000, thermal = ThermalStatus.SEVERE))
        val both = policy.update(sample(2_000, thermal = ThermalStatus.SEVERE, floorHeld = false))
        assertEquals(1_000L, both.capSinceMs)

        val worse = policy.update(sample(3_000, thermal = ThermalStatus.CRITICAL))
        assertEquals(3_000L, worse.capSinceMs)
    }

    @Test
    fun `thresholds keep their documented order`() {
        assertEquals(34f, HealthThresholds.TEMP_WARN_C, 0f)
        assertEquals(36f, HealthThresholds.TEMP_THROTTLE_C, 0f)
        assertEquals(40f, HealthThresholds.TEMP_PAUSE_C, 0f)
        assertEquals(50f, HealthThresholds.TEMP_CRITICAL_C, 0f)
        assertEquals(2f, HealthThresholds.TEMP_RELEASE_MARGIN_C, 0f)
        assertEquals(ThermalStatus.LIGHT, HealthThresholds.THERMAL_WARN)
        assertEquals(ThermalStatus.MODERATE, HealthThresholds.THERMAL_THROTTLE)
        assertEquals(ThermalStatus.SEVERE, HealthThresholds.THERMAL_PAUSE)
        assertEquals(ThermalStatus.CRITICAL, HealthThresholds.THERMAL_CRITICAL)
        assertTrue(HealthThresholds.TEMP_WARN_C < HealthThresholds.TEMP_THROTTLE_C)
        assertTrue(HealthThresholds.TEMP_THROTTLE_C < HealthThresholds.TEMP_PAUSE_C)
        assertTrue(HealthThresholds.TEMP_PAUSE_C < HealthThresholds.TEMP_CRITICAL_C)
        assertEquals(ThermalStatus.SEVERE, ThermalStatus.fromPlatform(3))
        assertEquals(ThermalStatus.SHUTDOWN, ThermalStatus.fromPlatform(99))
    }
}
