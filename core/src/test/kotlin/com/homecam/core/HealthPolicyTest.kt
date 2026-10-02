package com.homecam.core

import com.homecam.core.health.Axis
import com.homecam.core.health.Cap
import com.homecam.core.health.HealthConfig
import com.homecam.core.health.HealthPolicy
import com.homecam.core.health.HealthSample
import com.homecam.core.health.HealthThresholds
import com.homecam.core.health.Level
import com.homecam.core.health.ThermalStatus
import org.junit.Assert.assertEquals
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

    @Test
    fun `thermal status maps to a level and a cap`() {
        val rows = listOf(
            ThermalStatus.NONE to (Level.OK to Cap.NONE),
            ThermalStatus.LIGHT to (Level.OK to Cap.NONE),
            ThermalStatus.MODERATE to (Level.WARN to Cap.NONE),
            ThermalStatus.SEVERE to (Level.PAUSE to Cap.NO_CONTINUOUS),
            ThermalStatus.CRITICAL to (Level.CRITICAL to Cap.NO_RECORDING),
            ThermalStatus.SHUTDOWN to (Level.CRITICAL to Cap.NO_RECORDING),
        )
        for ((status, expected) in rows) {
            val state = HealthPolicy().update(sample(thermal = status))
            assertEquals("$status", expected, state.thermal.level to state.cap)
        }
    }

    @Test
    fun `without a thermal status the battery temperature stands in`() {
        val rows = listOf(
            35f to Level.OK, 40f to Level.WARN, 44.9f to Level.WARN,
            45f to Level.PAUSE, 49.9f to Level.PAUSE, 50f to Level.CRITICAL,
        )
        for ((temp, level) in rows) {
            val state = HealthPolicy().update(sample(thermal = null, tempC = temp))
            assertEquals("$temp", level, state.thermal.level)
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
    fun `moderate heat does not release a pause and a fresh spike restarts the dwell`() {
        val policy = HealthPolicy()
        policy.update(sample(0, thermal = ThermalStatus.SEVERE))
        policy.update(sample(1 * minute, thermal = ThermalStatus.LIGHT))
        policy.update(sample(8 * minute, thermal = ThermalStatus.MODERATE))
        val state = policy.update(sample(30 * minute, thermal = ThermalStatus.MODERATE))
        assertEquals(Level.PAUSE, state.thermal.level)
    }

    @Test
    fun `critical steps down to pause and then to ok`() {
        val policy = HealthPolicy()
        policy.update(sample(0, thermal = ThermalStatus.CRITICAL))
        policy.update(sample(1 * minute, thermal = ThermalStatus.SEVERE))
        assertEquals(Level.PAUSE, policy.update(sample(12 * minute, thermal = ThermalStatus.SEVERE)).thermal.level)
        policy.update(sample(13 * minute, thermal = ThermalStatus.LIGHT))
        assertEquals(Level.OK, policy.update(sample(24 * minute, thermal = ThermalStatus.LIGHT)).thermal.level)
    }

    @Test
    fun `thermal auto-pause off disables only the pause rung`() {
        val policy = HealthPolicy(HealthConfig(thermalAutoPause = false))
        assertEquals(Cap.NONE, policy.update(sample(0, thermal = ThermalStatus.SEVERE)).cap)
        assertEquals(Cap.NO_RECORDING, policy.update(sample(1, thermal = ThermalStatus.CRITICAL)).cap)
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
        assertTrue(HealthThresholds.TEMP_WARN_C < HealthThresholds.TEMP_PAUSE_C)
        assertTrue(HealthThresholds.TEMP_PAUSE_C < HealthThresholds.TEMP_CRITICAL_C)
        assertEquals(ThermalStatus.SEVERE, ThermalStatus.fromPlatform(3))
        assertEquals(ThermalStatus.SHUTDOWN, ThermalStatus.fromPlatform(99))
    }
}
