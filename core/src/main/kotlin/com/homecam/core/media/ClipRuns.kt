package com.homecam.core.media

import com.homecam.proto.Clip

/**
 * Continuous recording writes one clip every couple of minutes, so a night is hundreds of
 * rows. A run is a stretch of clips each starting where the last one ended — one thing to
 * look at, not sixty.
 */
data class ClipRun(val clips: List<Clip>) {
    val startedAtMs: Long get() = clips.minOf { it.startedAtMs }
    val endedAtMs: Long get() = clips.maxOf { it.startedAtMs + it.durationMs }
    val sizeBytes: Long get() = clips.sumOf { it.sizeBytes }
    val labels: List<String> get() = clips.flatMap { it.labelsList }.distinct().sorted()
    val pinned: Boolean get() = clips.any { it.pinned }
}

object ClipRuns {
    const val DEFAULT_MAX_GAP_MS = 15_000L

    /** Newest run first, and newest clip first inside each, whatever order [clips] came in. */
    fun group(clips: List<Clip>, maxGapMs: Long = DEFAULT_MAX_GAP_MS): List<ClipRun> {
        val runs = mutableListOf<MutableList<Clip>>()
        for (clip in clips.sortedBy { it.startedAtMs }) {
            val previous = runs.lastOrNull()?.last()
            val continues = previous != null &&
                clip.startedAtMs - (previous.startedAtMs + previous.durationMs) <= maxGapMs
            if (continues) runs.last() += clip else runs += mutableListOf(clip)
        }
        return runs.asReversed().map { ClipRun(it.asReversed()) }
    }
}
