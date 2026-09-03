package com.homecam.core.media

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Groups event timestamps into local-hour buckets for the timeline density strip (Phase 10).
 * Bucketing is done in the viewer's zone via [java.time], not by dividing epoch millis, so a
 * day with 23 or 25 hours across a daylight-saving change still lands each event in the wall
 * clock hour it actually happened.
 */
object TimelineBuckets {

    data class Bucket(val atMs: Long, val count: Int)

    fun hourly(eventTimesMs: List<Long>, zone: ZoneId): List<Bucket> {
        val counts = sortedMapOf<Long, Int>()
        for (atMs in eventTimesMs) {
            val hourStart = Instant.ofEpochMilli(atMs)
                .atZone(zone)
                .truncatedTo(ChronoUnit.HOURS)
                .toInstant()
                .toEpochMilli()
            counts[hourStart] = (counts[hourStart] ?: 0) + 1
        }
        return counts.map { Bucket(it.key, it.value) }
    }
}
