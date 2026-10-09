package com.homecam.core

import com.homecam.core.media.DetectionSample
import com.homecam.core.media.EventSpan
import com.homecam.core.media.EventSpans
import com.homecam.proto.Clip
import org.junit.Assert.assertEquals
import org.junit.Test

class EventSpansTest {

    private val clip = Clip.newBuilder().setId("c").setStartedAtMs(1_000_000).setDurationMs(120_000)
        .addLabels("person").build()

    private fun sample(offsetMs: Long, vararg labels: String) = DetectionSample(offsetMs, labels.toSet())

    @Test
    fun `a detection at a known offset comes back at that offset`() {
        val spans = EventSpans.forClip(clip, listOf(sample(40_000, "person")))

        assertEquals(listOf(EventSpan("c", "person", 1_040_000, 1_041_000)), spans)
    }

    @Test
    fun `samples within the merge gap are one span, and a longer silence starts another`() {
        val spans = EventSpans.forClip(
            clip,
            listOf(sample(10_000, "person"), sample(12_000, "person"), sample(14_500, "person"), sample(60_000, "person")),
        )

        assertEquals(
            listOf(EventSpan("c", "person", 1_010_000, 1_015_500), EventSpan("c", "person", 1_060_000, 1_061_000)),
            spans,
        )
    }

    @Test
    fun `each label is placed on its own`() {
        val spans = EventSpans.forClip(clip, listOf(sample(5_000, "person", "pet"), sample(30_000, "pet")))

        assertEquals(listOf("person" to 1_005_000L, "pet" to 1_005_000L, "pet" to 1_030_000L), spans.map { it.label to it.startMs }.sortedBy { it.second })
    }

    @Test
    fun `a span never runs past the end of its clip`() {
        val spans = EventSpans.forClip(clip, listOf(sample(119_800, "person")))

        assertEquals(1_120_000L, spans.single().endMs)
    }

    @Test
    fun `a label with no samples marks the whole clip`() {
        val sound = clip.toBuilder().clearLabels().addLabels("person").addLabels("sound").build()
        val spans = EventSpans.forClip(sound, listOf(sample(40_000, "person")))

        assertEquals(EventSpan("c", "sound", 1_000_000, 1_120_000), spans.single { it.label == "sound" })
        assertEquals(1_040_000L, spans.single { it.label == "person" }.startMs)
    }

    @Test
    fun `an unreadable track marks the whole clip under its labels`() {
        assertEquals(listOf(EventSpan("c", "person", 1_000_000, 1_120_000)), EventSpans.forClip(clip, emptyList()))
    }
}
