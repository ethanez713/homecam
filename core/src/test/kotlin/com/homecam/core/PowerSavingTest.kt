package com.homecam.core

import com.homecam.core.diagnostics.PowerSavingReason
import com.homecam.core.media.PowerSaving
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSavingTest {

    @Test
    fun `the tick engages only after the scene has been idle for the quiet period`() {
        val harness = Harness()
        harness.now = PowerSaving.QUIET_MS - 1
        harness.power.tick(sceneIdle = true)
        assertFalse(harness.saving)

        harness.now = PowerSaving.QUIET_MS
        harness.power.tick(sceneIdle = true)
        assertTrue(harness.saving)
        assertEquals(listOf(true to PowerSavingReason.IDLE), harness.log)
    }

    @Test
    fun `the tick never releases`() {
        val harness = Harness()
        harness.engage()
        harness.now += 60_000
        harness.power.tick(sceneIdle = false)
        harness.power.tick(sceneIdle = true)
        assertTrue(harness.saving)
        assertEquals(listOf(true to PowerSavingReason.IDLE), harness.log)
    }

    @Test
    fun `time the scene is busy does not count toward the quiet period`() {
        val harness = Harness()
        harness.now = 60_000
        harness.power.tick(sceneIdle = false)
        harness.power.tick(sceneIdle = true)
        assertFalse(harness.saving)

        harness.now += PowerSaving.QUIET_MS
        harness.power.tick(sceneIdle = true)
        assertTrue(harness.saving)
    }

    @Test
    fun `a release is immediate, restarts the quiet period, and does not log twice`() {
        val harness = Harness()
        harness.engage()
        val releasedAt = harness.now
        harness.power.release(PowerSavingReason.MOTION)
        assertFalse(harness.saving)
        assertEquals(PowerSavingReason.MOTION, harness.log.last().second)

        harness.power.release(PowerSavingReason.MOTION)
        assertEquals(2, harness.log.size)

        val quiet = PowerSaving.QUIET_MS * 2
        harness.now = releasedAt + quiet - 1
        harness.power.tick(sceneIdle = true)
        assertFalse(harness.saving)

        harness.now = releasedAt + quiet
        harness.power.tick(sceneIdle = true)
        assertTrue(harness.saving)
        assertEquals(true to PowerSavingReason.IDLE, harness.log.last())
    }

    @Test
    fun `each short engagement doubles the next quiet period up to the cap`() {
        val harness = Harness()
        val base = PowerSaving.QUIET_MS
        harness.engageThenReleaseShort(base)
        harness.engageThenReleaseShort(base * 2)
        harness.engageThenReleaseShort(base * 4)
        harness.engageThenReleaseShort(PowerSaving.QUIET_MS_CAP)
        harness.engageThenReleaseShort(PowerSaving.QUIET_MS_CAP)
    }

    @Test
    fun `an engagement that lasts the stable period resets the quiet period`() {
        val harness = Harness()
        harness.engageThenReleaseShort(PowerSaving.QUIET_MS)
        harness.tickUntilEngaged(PowerSaving.QUIET_MS * 2)
        harness.now += PowerSaving.STABLE_ENGAGED_MS
        harness.power.release(PowerSavingReason.DETECTION)
        assertFalse(harness.saving)
        harness.tickUntilEngaged(PowerSaving.QUIET_MS)
    }

    @Test
    fun `an engagement one millisecond short of stable doubles the quiet period`() {
        val harness = Harness()
        harness.tickUntilEngaged(PowerSaving.QUIET_MS)
        harness.now += PowerSaving.STABLE_ENGAGED_MS - 1
        harness.power.release(PowerSavingReason.MOTION)
        assertFalse(harness.saving)
        harness.tickUntilEngaged(PowerSaving.QUIET_MS * 2)
    }

    @Test
    fun `a trigger every 31 seconds settles at no more than 8 pairs an hour`() {
        val pairs = Harness().pairsOverHour(triggerEveryMs = 31_000)
        assertTrue("pairs=$pairs", pairs in 1..8)
    }

    @Test
    fun `a release while already at full rate still postpones engaging`() {
        val harness = Harness()
        harness.now = PowerSaving.QUIET_MS - 1
        harness.power.release(PowerSavingReason.SOUND)
        assertTrue(harness.log.isEmpty())

        harness.now = PowerSaving.QUIET_MS - 1 + PowerSaving.QUIET_MS - 1
        harness.power.tick(sceneIdle = true)
        assertFalse(harness.saving)

        harness.now = PowerSaving.QUIET_MS - 1 + PowerSaving.QUIET_MS
        harness.power.tick(sceneIdle = true)
        assertTrue(harness.saving)
    }

    @Test
    fun `each trigger releases with its own reason`() {
        assertReason(PowerSavingReason.VIEWER, "viewer")
        assertReason(PowerSavingReason.DETECTION, "detection")
        assertReason(PowerSavingReason.SOUND, "sound")
        assertReason(PowerSavingReason.MOTION, "motion")
        assertReason(PowerSavingReason.MANUAL_CLIP, "manual_clip")
    }

    @Test
    fun `an engage the capturer refuses is retried once it will take it`() {
        var accept = false
        var saving = false
        var now = 0L
        val log = mutableListOf<Pair<Boolean, String>>()
        val power = PowerSaving(
            nowMs = { now },
            setSaving = { next ->
                if (next && !accept) {
                    false
                } else if (next == saving) {
                    false
                } else {
                    saving = next
                    true
                }
            },
            log = { engaged, reason -> log += engaged to reason },
        )
        now = PowerSaving.QUIET_MS
        power.tick(sceneIdle = true)
        assertFalse(saving)
        assertTrue(log.isEmpty())
        assertFalse(power.isEngaged)

        accept = true
        power.tick(sceneIdle = true)
        assertTrue(saving)
        assertEquals(listOf(true to PowerSavingReason.IDLE), log)
    }

    private fun assertReason(reason: String, wire: String) {
        val harness = Harness()
        harness.engage()
        harness.power.release(reason)
        assertFalse(harness.power.isEngaged)
        assertEquals(false to wire, harness.log.last())
    }

    private class Harness {
        var now = 0L
        var saving = false
        val log = mutableListOf<Pair<Boolean, String>>()
        val power = PowerSaving(
            nowMs = { now },
            setSaving = ::update,
            log = { engaged, reason -> log += engaged to reason },
        )

        fun update(next: Boolean): Boolean {
            if (next == saving) return false
            saving = next
            return true
        }

        fun engage() {
            now = PowerSaving.QUIET_MS
            power.tick(sceneIdle = true)
            check(saving)
        }

        fun tickUntilEngaged(quiet: Long) {
            val start = now
            now = start + quiet - 1
            power.tick(sceneIdle = true)
            assertFalse(saving)
            now = start + quiet
            power.tick(sceneIdle = true)
            assertTrue(saving)
        }

        fun engageThenReleaseShort(quiet: Long) {
            tickUntilEngaged(quiet)
            power.release(PowerSavingReason.MOTION)
            assertFalse(saving)
        }

        fun pairsOverHour(triggerEveryMs: Long): Int {
            val hour = 60 * 60 * 1000L
            var pairs = 0
            var nextTrigger = triggerEveryMs
            var t = 0L
            while (t <= hour) {
                now = t
                if (t == nextTrigger) {
                    val wasEngaged = saving
                    power.release(PowerSavingReason.MOTION)
                    if (wasEngaged) pairs += 1
                    nextTrigger += triggerEveryMs
                }
                power.tick(sceneIdle = true)
                t += 1_000
            }
            return pairs
        }
    }
}
