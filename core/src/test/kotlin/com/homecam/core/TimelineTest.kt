package com.homecam.core

import com.homecam.core.media.EventSpan
import com.homecam.core.media.TickSteps
import com.homecam.core.media.Timeline
import com.homecam.proto.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    private fun clip(id: String, startS: Long, seconds: Long, vararg labels: String) =
        Clip.newBuilder().setId(id).setStartedAtMs(startS * 1000).setDurationMs((seconds * 1000).toInt())
            .addAllLabels(labels.toList()).build()

    // Two back-to-back segments, a gap, then an event clip and another segment.
    private val timeline = Timeline(
        listOf(
            clip("d", 1000, 120),
            clip("a", 0, 120),
            clip("b", 120, 120, "person"),
            clip("c", 600, 20, "sound"),
        )
    )

    @Test
    fun `an instant inside a clip finds it, whatever order the clips arrived in`() {
        assertEquals("a", timeline.clipAt(60_000)?.id)
        assertEquals("b", timeline.clipAt(120_000)?.id)
        assertEquals("d", timeline.clipAt(1_100_000)?.id)
    }

    @Test
    fun `an instant in a gap finds nothing, and playback resumes at the next clip`() {
        assertNull(timeline.clipAt(400_000))
        assertEquals("c", timeline.firstClipFrom(400_000)?.id)
        assertNull(timeline.firstClipFrom(2_000_000))
    }

    @Test
    fun `the span runs from the first clip's start to the last one's end`() {
        assertEquals(0, timeline.startMs)
        assertEquals(1_120_000, timeline.endMs)
        assertEquals(1_120_000, timeline.clamp(9_999_999))
        assertEquals(0, timeline.clamp(-5))
    }

    @Test
    fun `back-to-back segments play through, a pause in recording does not`() {
        assertEquals("b", timeline.continuationOf(timeline.clipAt(0)!!)?.id)
        assertNull(timeline.continuationOf(timeline.clipAt(130_000)!!))
    }

    @Test
    fun `events are the labelled clips, in order`() {
        assertEquals(listOf("b", "c"), timeline.events.map { it.clipId })
        assertEquals("b", timeline.nextEvent(0)?.clipId)
        assertEquals("c", timeline.nextEvent(120_000)?.clipId)
        assertNull(timeline.nextEvent(600_000))
    }

    @Test
    fun `stepping back restarts the event you are in before going to the one before`() {
        assertEquals("c", timeline.previousEvent(610_000)?.clipId)
        assertEquals("b", timeline.previousEvent(601_000)?.clipId)
        assertNull(timeline.previousEvent(121_000))
    }

    @Test
    fun `placed spans replace the whole-clip event, and next event lands on their seconds`() {
        val placed = Timeline(
            timeline.clips,
            listOf(EventSpan("b", "person", 150_000, 156_000), EventSpan("b", "person", 200_000, 203_000)),
        )

        assertEquals(listOf(150_000L, 200_000L, 600_000L), placed.events.map { it.startMs })
        assertEquals(150_000L, placed.nextEvent(0)?.startMs)
        assertEquals(200_000L, placed.nextEvent(150_000)?.startMs)
        assertEquals(150_000L, placed.previousEvent(200_000)?.startMs)
    }

    @Test
    fun `ruler marks get coarser as the view zooms out and never crowd`() {
        val close = TickSteps.forScale(msPerDp = 2_000f)
        val far = TickSteps.forScale(msPerDp = 200_000f)

        assertTrue(close.minorMs < far.minorMs)
        listOf(500f, 2_000f, 12_000f, 60_000f, 200_000f).forEach { scale ->
            val steps = TickSteps.forScale(scale)
            assertTrue("minor marks crowd at $scale", steps.minorMs / scale >= 7f)
            assertEquals(0, steps.labelMs % steps.minorMs)
        }
    }

    @Test
    fun `an empty timeline has no span and no events`() {
        val empty = Timeline(emptyList())
        assertTrue(empty.isEmpty)
        assertNull(empty.clipAt(0))
        assertNull(empty.nextEvent(0))
    }
}
