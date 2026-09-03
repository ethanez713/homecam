package com.homecam.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.data.ClipEntity
import com.homecam.app.data.HomecamDatabase
import com.homecam.core.media.TimelineBuckets
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * Phase 10.1: the density-strip counts for a range must total the number of events the
 * timeline lists for that same range, so the strip and the list can never disagree. Both
 * derive from the same (clip × label) expansion; this pins that against a real database.
 */
@RunWith(AndroidJUnit4::class)
class TimelineCountTest {

    private lateinit var database: HomecamDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, HomecamDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    private fun clip(id: String, atMs: Long, labels: String) = ClipEntity(
        id = id, path = "/x/$id.mp4", startedAtMs = atMs, durationMs = 1000, sizeBytes = 10,
        width = 320, height = 240, hasAudio = false, pinned = false, labels = labels,
    )

    @Test
    fun bucketCountsTotalTheTimelineEvents() = runTest {
        val base = 1_700_000_000_000L
        database.clips().upsert(clip("a", base, "person,vehicle"))
        database.clips().upsert(clip("b", base + 3_600_000, "pet"))
        database.clips().upsert(clip("c", base + 7_200_000, ""))

        val rows = database.clips().between(0, Long.MAX_VALUE, 1000)
        val timelineEvents = rows.sumOf { row ->
            row.labels.split(",").filter { it.isNotBlank() }.ifEmpty { listOf("motion") }.size
        }
        val eventTimes = rows.flatMap { row ->
            row.labels.split(",").filter { it.isNotBlank() }.ifEmpty { listOf("motion") }.map { row.startedAtMs }
        }
        val bucketTotal = TimelineBuckets.hourly(eventTimes, ZoneId.systemDefault()).sumOf { it.count }

        assertEquals("2 + 1 + 1(motion) = 4 events", 4, timelineEvents)
        assertEquals(timelineEvents, bucketTotal)
    }
}
