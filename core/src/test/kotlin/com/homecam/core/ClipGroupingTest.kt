package com.homecam.core

import com.homecam.core.media.ClipGrouping
import com.homecam.proto.Clip
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipGroupingTest {

    private fun clip(id: String, startS: Long, seconds: Long, lensId: String) =
        Clip.newBuilder().setId(id).setStartedAtMs(startS * 1000).setDurationMs((seconds * 1000).toInt())
            .setLensId(lensId).build()

    private fun ids(clips: List<Clip>) = ClipGrouping.group(clips).map { group -> group.clips.map { it.id } }

    @Test
    fun `overlapping clips from different lenses form one group around the earliest`() {
        val wide = clip("wide", 0, 30, "back-wide")
        val front = clip("front", 10, 30, "front")

        val group = ClipGrouping.group(listOf(front, wide)).single()

        assertEquals(listOf("wide", "front"), group.clips.map { it.id })
        assertEquals("wide", group.primary.id)
    }

    @Test
    fun `clips that do not overlap stay in separate groups`() {
        // [0, 10s) and [10s, 20s) share an endpoint and still do not overlap.
        val wide = clip("wide", 0, 10, "back-wide")
        val front = clip("front", 10, 10, "front")

        assertEquals(listOf(listOf("wide"), listOf("front")), ids(listOf(wide, front)))
    }

    @Test
    fun `a clip with no lens id stays alone and does not join the lenses it overlaps`() {
        val wide = clip("wide", 0, 20, "back-wide")
        val unknown = clip("unknown", 10, 30, "")
        val front = clip("front", 30, 20, "front")

        assertEquals(listOf(listOf("wide"), listOf("unknown"), listOf("front")), ids(listOf(wide, unknown, front)))
    }

    @Test
    fun `a third clip that repeats a lens already in the overlap starts its own group`() {
        val wide = clip("wide", 0, 100, "back-wide")
        val front = clip("front", 50, 100, "front")
        val again = clip("wide-2", 80, 100, "back-wide")

        assertEquals(listOf(listOf("wide", "front"), listOf("wide-2")), ids(listOf(wide, front, again)))
    }

    @Test
    fun `a chain of overlaps across distinct lenses is one group`() {
        val wide = clip("wide", 0, 100, "back-wide")
        val front = clip("front", 90, 100, "front")
        val ultra = clip("ultra", 180, 40, "back-ultrawide")

        val group = ClipGrouping.group(listOf(ultra, wide, front)).single()

        assertEquals(listOf("wide", "front", "ultra"), group.clips.map { it.id })
    }

    @Test
    fun `groups follow the order the clips were given`() {
        val later = clip("later", 500, 10, "back-wide")
        val wide = clip("wide", 0, 10, "back-wide")
        val front = clip("front", 0, 10, "front")

        val groups = ClipGrouping.group(listOf(later, front, wide))

        assertEquals(listOf("later"), groups[0].clips.map { it.id })
        assertEquals(listOf("front", "wide"), groups[1].clips.map { it.id })
        assertEquals("front", groups[1].primary.id)
    }
}
