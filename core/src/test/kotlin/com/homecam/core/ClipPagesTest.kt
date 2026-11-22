package com.homecam.core

import com.homecam.core.media.ClipPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipPagesTest {

    private fun row(id: String, startedAtMs: Long) = ClipPages.Row(id, startedAtMs)

    /** Newest first, `startedAtMs <= until`, the way the camera answers CLIP_LIST. */
    private fun cameraPage(all: List<ClipPages.Row>, untilMs: Long?, pageSize: Int): List<ClipPages.Row> =
        all.filter { untilMs == null || it.startedAtMs <= untilMs }
            .sortedWith(compareByDescending<ClipPages.Row> { it.startedAtMs }.thenBy { it.id })
            .take(pageSize)

    private fun load(all: List<ClipPages.Row>, pageSize: Int): Pair<List<ClipPages.Row>, Int> {
        val kept = mutableListOf<ClipPages.Row>()
        var seen = emptySet<String>()
        var until: Long? = null
        var requests = 0
        while (requests < 20) {
            requests++
            val step = ClipPages.absorb(seen, cameraPage(all, until, pageSize), pageSize)
            kept += step.added
            val next = step.nextUntilMs ?: return kept to requests
            seen = step.seen
            until = next
        }
        error("paging did not stop")
    }

    @Test
    fun `clips that share a millisecond on either side of a page are both kept`() {
        val all = listOf(
            row("a", 600),
            row("b", 500),
            row("c", 400),
            row("d", 400),
            row("e", 300),
        )
        val first = ClipPages.absorb(emptySet(), cameraPage(all, null, 3), 3)
        assertEquals(listOf("a", "b", "c"), first.added.map { it.id })
        assertEquals(400L, first.nextUntilMs)

        val (kept, requests) = load(all, pageSize = 3)
        assertEquals(listOf("a", "b", "c", "d", "e"), kept.map { it.id })
        assertEquals(3, requests)
    }

    @Test
    fun `a short last page is kept and the walk stops`() {
        val all = listOf(row("a", 4), row("b", 3), row("c", 2), row("d", 1))

        val (kept, requests) = load(all, pageSize = 3)

        assertEquals(listOf("a", "b", "c", "d"), kept.map { it.id })
        assertEquals(2, requests)
    }

    @Test
    fun `an empty camera yields nothing and stops`() {
        val (kept, requests) = load(emptyList(), pageSize = 3)

        assertEquals(emptyList<ClipPages.Row>(), kept)
        assertEquals(1, requests)
    }

    @Test
    fun `a full page of ids already seen does not ask again`() {
        val step = ClipPages.absorb(
            setOf("a", "b"),
            listOf(row("a", 5), row("b", 4)),
            pageSize = 2,
        )

        assertEquals(emptyList<ClipPages.Row>(), step.added)
        assertNull(step.nextUntilMs)
    }
}
