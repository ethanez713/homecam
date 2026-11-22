package com.homecam.core.media

/**
 * One page of the camera's clip list is newest-first and capped. The next page asks
 * again for the oldest [Row.startedAtMs] on the page just read: the camera's bound is
 * inclusive, and two lenses can start a clip in the same millisecond, so stepping back
 * one would drop the other lens. The row that comes back twice is ignored by id. A
 * short page has reached the oldest clip; a full page that adds no id would otherwise
 * ask for that same bound forever.
 */
object ClipPages {
    const val PAGE_SIZE = 1_000

    data class Row(val id: String, val startedAtMs: Long)

    data class Step(
        val added: List<Row>,
        val seen: Set<String>,
        val nextUntilMs: Long?,
    )

    fun absorb(seen: Set<String>, page: List<Row>, pageSize: Int = PAGE_SIZE): Step {
        val added = page.filter { it.id !in seen }
        val finished = page.size < pageSize || added.isEmpty()
        return Step(
            added = added,
            seen = seen + added.map { it.id },
            nextUntilMs = if (finished) null else page.minOf { it.startedAtMs },
        )
    }
}
