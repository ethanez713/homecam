package com.homecam.core.media

import com.homecam.proto.Clip

/**
 * Clips recorded together on different lenses are one event. Two clips share a group when
 * their half-open ranges `[startedAtMs, startedAtMs + durationMs)` overlap and their lens
 * ids differ; a clip with no lens id stays alone, and a group never holds two clips of one
 * lens. Overlap is transitive across a chain while every lens in it is still distinct, so
 * a second clip of a lens already in the group starts a group of its own.
 *
 * Groups come back in the order their clips first appeared in [clips]. Inside a group the
 * earliest-starting clip is first — the one a row shows until a lens is picked. Equal
 * start times keep the order of [clips].
 */
data class ClipGroup(val clips: List<Clip>) {
    val primary: Clip get() = clips.first()

    /** Identity of the membership, independent of order. */
    val key: String get() = clips.map { it.id }.sorted().joinToString("\u0000")
}

object ClipGrouping {

    fun group(clips: List<Clip>): List<ClipGroup> {
        data class Indexed(val clip: Clip, val index: Int)

        val indexed = clips.mapIndexed { index, clip -> Indexed(clip, index) }
        val clusters = mutableListOf<MutableList<Indexed>>()
        for (item in indexed.sortedWith(compareBy({ it.clip.startedAtMs }, { it.index }))) {
            val lens = item.clip.lensId
            val host = if (lens.isEmpty()) {
                null
            } else {
                clusters.firstOrNull { cluster ->
                    cluster.none { it.clip.lensId.isEmpty() || it.clip.lensId == lens } &&
                        cluster.any { overlaps(it.clip, item.clip) }
                }
            }
            if (host != null) host += item else clusters += mutableListOf(item)
        }
        return clusters
            .sortedBy { cluster -> cluster.minOf { it.index } }
            .map { cluster ->
                ClipGroup(cluster.sortedWith(compareBy({ it.clip.startedAtMs }, { it.index })).map { it.clip })
            }
    }

    private fun overlaps(a: Clip, b: Clip): Boolean {
        val aEnd = a.startedAtMs + a.durationMs
        val bEnd = b.startedAtMs + b.durationMs
        return a.startedAtMs < bEnd && b.startedAtMs < aEnd
    }
}
