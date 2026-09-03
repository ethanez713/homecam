package com.homecam.core

import com.homecam.core.media.ClipFilter
import com.homecam.core.media.TimelineBuckets
import com.homecam.core.retention.ClipRef
import com.homecam.core.retention.RetentionLimits
import com.homecam.core.retention.RetentionPlanner
import com.homecam.proto.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class MediaBrowsingTest {

    private val newYork = ZoneId.of("America/New_York")

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 3, 8, hour, minute, 0, 0, newYork).toInstant().toEpochMilli()

    private fun localHour(epochMs: Long): Int =
        java.time.Instant.ofEpochMilli(epochMs).atZone(newYork).hour

    @Test
    fun `events bucket by local hour across the spring-forward gap`() {
        // 01:30 EST and 03:30 EDT are one epoch-hour apart but two wall-clock hours apart,
        // because 02:00 does not exist that morning.
        val buckets = TimelineBuckets.hourly(listOf(at(1, 30), at(3, 30)), newYork)
        assertEquals("two distinct hour buckets", 2, buckets.size)
        assertEquals(listOf(1, 3), buckets.map { localHour(it.atMs) })
        assertEquals(2, buckets.sumOf { it.count })
    }

    @Test
    fun `events in the same local hour share a bucket`() {
        val buckets = TimelineBuckets.hourly(listOf(at(4, 5), at(4, 55)), newYork)
        assertEquals(1, buckets.size)
        assertEquals(2, buckets.first().count)
        assertEquals(
            ZonedDateTime.of(2026, 3, 8, 4, 0, 0, 0, newYork).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli(),
            buckets.first().atMs,
        )
    }

    @Test
    fun `filtering by label returns exactly the clips carrying it`() {
        val clips = listOf(
            clip("a", listOf("person")),
            clip("b", listOf("vehicle", "person")),
            clip("c", listOf("pet")),
        )
        assertEquals(listOf("a", "b"), ClipFilter.withLabel(clips, "person").map { it.id })
        assertEquals(listOf("c"), ClipFilter.withLabel(clips, "pet").map { it.id })
        assertTrue(ClipFilter.withLabel(clips, "package").isEmpty())
    }

    @Test
    fun `pinned near the ceiling is flagged, and comfortably under is not`() {
        val gb = RetentionLimits.GB
        val limits = RetentionLimits(maxAgeDays = 14, maxSizeGb = 10, minFreeSpaceGb = 1)
        val near = listOf(ClipRef("p", 0, (9.5 * gb).toLong(), pinned = true))
        val under = listOf(ClipRef("p", 0, (3 * gb), pinned = true))
        assertTrue(RetentionPlanner.pinnedNearCeiling(near, limits))
        assertFalse(RetentionPlanner.pinnedNearCeiling(under, limits))
    }

    private fun clip(id: String, labels: List<String>): Clip =
        Clip.newBuilder().setId(id).addAllLabels(labels).build()
}
