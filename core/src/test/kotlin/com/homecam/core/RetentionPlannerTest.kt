package com.homecam.core

import com.homecam.core.retention.ClipRef
import com.homecam.core.retention.RetentionLimits
import com.homecam.core.retention.RetentionPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 24 * 60 * 60 * 1000L
private const val GB = RetentionLimits.GB

class RetentionPlannerTest {

    private val now = 1_700_000_000_000L

    private fun clip(id: String, ageDays: Long, gb: Long = 1, pinned: Boolean = false) =
        ClipRef(id, now - ageDays * DAY, gb * GB, pinned)

    @Test
    fun `clips older than maxAgeDays are deleted`() {
        val plan = RetentionPlanner.plan(
            clips = listOf(clip("old", 20), clip("fresh", 2)),
            limits = RetentionLimits(maxAgeDays = 14, maxSizeGb = 100, minFreeSpaceGb = 1),
            nowMs = now,
            deviceFreeBytes = 500 * GB,
        )
        assertEquals(listOf("old"), plan.delete.map { it.id })
    }

    @Test
    fun `the oldest clips go when the size ceiling is exceeded`() {
        val clips = (1..10).map { clip("c$it", ageDays = (11 - it).toLong(), gb = 1) }
        val plan = RetentionPlanner.plan(
            clips = clips,
            limits = RetentionLimits(maxAgeDays = 365, maxSizeGb = 6, minFreeSpaceGb = 1),
            nowMs = now,
            deviceFreeBytes = 500 * GB,
        )
        assertEquals(listOf("c1", "c2", "c3", "c4"), plan.delete.map { it.id })
        assertEquals(6 * GB, plan.remainingBytes)
    }

    @Test
    fun `low device free space prunes even when age and size are fine`() {
        val clips = (1..5).map { clip("c$it", ageDays = (6 - it).toLong(), gb = 2) }
        val plan = RetentionPlanner.plan(
            clips = clips,
            limits = RetentionLimits(maxAgeDays = 365, maxSizeGb = 100, minFreeSpaceGb = 8),
            nowMs = now,
            deviceFreeBytes = 3 * GB,
        )
        assertEquals(listOf("c1", "c2", "c3"), plan.delete.map { it.id })
    }

    @Test
    fun `pinned clips survive every limit`() {
        val plan = RetentionPlanner.plan(
            clips = listOf(
                clip("ancient-pinned", 400, gb = 4, pinned = true),
                clip("old", 300, gb = 4),
                clip("recent", 1, gb = 4),
            ),
            limits = RetentionLimits(maxAgeDays = 14, maxSizeGb = 1, minFreeSpaceGb = 100),
            nowMs = now,
            deviceFreeBytes = 0,
        )
        assertEquals(setOf("old", "recent"), plan.delete.map { it.id }.toSet())
        assertEquals(4 * GB, plan.pinnedBytes)
    }

    @Test
    fun `pinned clips approaching the ceiling are flagged`() {
        val limits = RetentionLimits(maxSizeGb = 10)
        val quiet = RetentionPlanner.plan(
            listOf(clip("p", 1, gb = 5, pinned = true)), limits, now, 500 * GB,
        )
        assertFalse(quiet.pinnedNearCeiling)

        val loud = RetentionPlanner.plan(
            listOf(clip("p", 1, gb = 10, pinned = true)), limits, now, 500 * GB,
        )
        assertTrue(loud.pinnedNearCeiling)
    }

    @Test
    fun `an empty library plans nothing`() {
        val plan = RetentionPlanner.plan(emptyList(), RetentionLimits(), now, 1 * GB)
        assertTrue(plan.delete.isEmpty())
        assertEquals(0L, plan.remainingBytes)
    }

    @Test
    fun `projected retention is capped by whichever limit bites first`() {
        // 10 clips of 1 GB over 10 days = 1 GB/day, against a 6 GB ceiling.
        val clips = (1..10).map { clip("c$it", ageDays = (11 - it).toLong(), gb = 1) }
        val bySize = RetentionPlanner.projectedRetentionDays(
            clips, RetentionLimits(maxAgeDays = 365, maxSizeGb = 6), now,
        )!!
        assertEquals(6.0, bySize, 0.5)

        val byAge = RetentionPlanner.projectedRetentionDays(
            clips, RetentionLimits(maxAgeDays = 3, maxSizeGb = 1000), now,
        )!!
        assertEquals(3.0, byAge, 0.001)
    }

    @Test
    fun `projected retention says nothing rather than guessing from one clip`() {
        assertNull(RetentionPlanner.projectedRetentionDays(listOf(clip("c", 1)), RetentionLimits(), now))
    }
}
