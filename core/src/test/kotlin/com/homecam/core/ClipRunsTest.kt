package com.homecam.core

import com.homecam.core.media.ClipRuns
import com.homecam.proto.Clip
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipRunsTest {

    private fun clip(id: String, startS: Long, seconds: Long, vararg labels: String, pinned: Boolean = false) =
        Clip.newBuilder().setId(id).setStartedAtMs(startS * 1000).setDurationMs((seconds * 1000).toInt())
            .setSizeBytes(100).addAllLabels(labels.toList()).setPinned(pinned).build()

    @Test
    fun `back-to-back segments become one run, newest first`() {
        val runs = ClipRuns.group(listOf(clip("a", 0, 120), clip("b", 120, 120), clip("c", 240, 120)))

        assertEquals(1, runs.size)
        assertEquals(listOf("c", "b", "a"), runs.single().clips.map { it.id })
        assertEquals(0, runs.single().startedAtMs)
        assertEquals(360_000, runs.single().endedAtMs)
        assertEquals(300, runs.single().sizeBytes)
    }

    @Test
    fun `a pause longer than the gap starts a new run`() {
        val runs = ClipRuns.group(listOf(clip("a", 0, 20), clip("b", 30, 20), clip("c", 500, 20)))

        assertEquals(listOf(listOf("c"), listOf("b", "a")), runs.map { run -> run.clips.map { it.id } })
    }

    @Test
    fun `a run carries the labels and pin of every clip in it`() {
        val run = ClipRuns.group(
            listOf(clip("a", 0, 120, "person"), clip("b", 120, 120, pinned = true), clip("c", 240, 120, "pet", "person"))
        ).single()

        assertEquals(listOf("person", "pet"), run.labels)
        assertEquals(true, run.pinned)
    }

    @Test
    fun `order of the input does not matter`() {
        val runs = ClipRuns.group(listOf(clip("c", 240, 120), clip("a", 0, 120), clip("b", 120, 120)))

        assertEquals(listOf("c", "b", "a"), runs.single().clips.map { it.id })
    }
}
