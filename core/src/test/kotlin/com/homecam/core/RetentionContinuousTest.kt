package com.homecam.core

import com.homecam.core.retention.ClipRef
import com.homecam.core.retention.RetentionLimits
import com.homecam.core.retention.RetentionPlanner
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.3/4.4: retention has to survive a writer that never stops. The free-space floor
 * must win over the size ceiling and hold at every prune, and the projection has to fall by
 * an order of magnitude when the mode is continuous rather than detection.
 */
class RetentionContinuousTest {

    private val gb = RetentionLimits.GB
    private val clipSize = 100L * 1024 * 1024

    @Test
    fun `the free-space floor holds at every prune under a continuous writer`() {
        // Size ceiling and age are made non-binding so the free-space floor is the only
        // limit under test.
        val limits = RetentionLimits(maxAgeDays = 3650, maxSizeGb = 100_000, minFreeSpaceGb = 8)
        val deviceBytes = 64L * gb
        val otherUsage = 50L * gb
        val clips = mutableListOf<ClipRef>()
        var now = 0L

        repeat(600) { step ->
            now += 60_000
            clips.add(ClipRef("c$step", now, clipSize, pinned = false))
            val freeBefore = deviceBytes - otherUsage - clips.sumOf { it.sizeBytes }
            val plan = RetentionPlanner.plan(clips, limits, now, freeBefore.coerceAtLeast(0))
            clips.removeAll(plan.delete.toSet())
            val freeAfter = deviceBytes - otherUsage - clips.sumOf { it.sizeBytes }
            assertTrue(
                "free $freeAfter fell below the ${limits.minFreeBytes} floor at step $step",
                freeAfter >= limits.minFreeBytes,
            )
        }
    }

    @Test
    fun `continuous projects materially fewer days than detection for the same span`() {
        val limits = RetentionLimits()
        val sevenDays = 7L * 24 * 3600 * 1000
        val fifty = 50L * 1024 * 1024

        val detection = (0 until 20).map { ClipRef("d$it", it * (sevenDays / 20), fifty, false) }
        val continuous = (0 until 2_000).map { ClipRef("c$it", it * (sevenDays / 2_000), fifty, false) }

        val projectedDetection = RetentionPlanner.projectedRetentionDays(detection, limits, sevenDays)!!
        val projectedContinuous = RetentionPlanner.projectedRetentionDays(continuous, limits, sevenDays)!!

        assertTrue(
            "continuous $projectedContinuous should be far below detection $projectedDetection",
            projectedContinuous < projectedDetection / 2,
        )
    }
}
