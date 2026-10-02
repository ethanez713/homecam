package com.homecam.core.media

import com.homecam.proto.Clip

/**
 * Recorded footage laid along a time axis: which clip covers an instant, where the gaps
 * are, and where the events worth jumping to begin. An event is a clip something was
 * detected in; inside a continuous segment that is as fine as the index goes, since the
 * exact moments live in the clip's own metadata track.
 */
class Timeline(clips: List<Clip>) {

    val clips: List<Clip> = clips.sortedBy { it.startedAtMs }
    val events: List<Clip> = this.clips.filter { it.labelsCount > 0 }

    val isEmpty: Boolean get() = clips.isEmpty()
    val startMs: Long get() = clips.firstOrNull()?.startedAtMs ?: 0L
    val endMs: Long get() = clips.maxOfOrNull { it.endMs } ?: 0L

    fun clamp(timeMs: Long): Long = timeMs.coerceIn(startMs, endMs)

    /** The clip recorded at [timeMs], or null in a gap. */
    fun clipAt(timeMs: Long): Clip? = clips.lastOrNull { it.startedAtMs <= timeMs }?.takeIf { timeMs < it.endMs }

    /** The first clip beginning at or after [timeMs]: where playback resumes after a gap. */
    fun firstClipFrom(timeMs: Long): Clip? = clips.firstOrNull { it.startedAtMs >= timeMs }

    /**
     * The clip that carries straight on from [clip], if recording did: back-to-back
     * segments play through as one recording, and a pause in recording stops playback.
     */
    fun continuationOf(clip: Clip, maxGapMs: Long = ClipRuns.DEFAULT_MAX_GAP_MS): Clip? =
        clips.firstOrNull { it.startedAtMs >= clip.endMs - OVERLAP_SLACK_MS && it.id != clip.id }
            ?.takeIf { it.startedAtMs - clip.endMs <= maxGapMs }

    fun nextEvent(afterMs: Long): Clip? = events.firstOrNull { it.startedAtMs > afterMs }

    /**
     * Stepping back from the middle of an event goes to its own start first, as "previous
     * track" does in a music player; from its first moments, to the event before.
     */
    fun previousEvent(beforeMs: Long): Clip? = events.lastOrNull { it.startedAtMs < beforeMs - RESTART_WINDOW_MS }

    private val Clip.endMs: Long get() = startedAtMs + durationMs

    companion object {
        const val RESTART_WINDOW_MS = 3_000L
        private const val OVERLAP_SLACK_MS = 500L
    }
}

/** Which marks a ruler draws at a given zoom, so they stay a readable distance apart. */
data class TickSteps(val minorMs: Long, val majorMs: Long, val labelMs: Long) {
    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE

        private val ladder = listOf(
            TickSteps(10_000L, MINUTE, MINUTE),
            TickSteps(MINUTE, 5 * MINUTE, 5 * MINUTE),
            TickSteps(MINUTE, 5 * MINUTE, 15 * MINUTE),
            TickSteps(5 * MINUTE, 30 * MINUTE, HOUR),
            TickSteps(15 * MINUTE, HOUR, 3 * HOUR),
            TickSteps(HOUR, 6 * HOUR, 12 * HOUR),
            TickSteps(3 * HOUR, 24 * HOUR, 24 * HOUR),
        )

        /** The finest step whose minor marks are at least [minGapDp] apart and labels [minLabelDp]. */
        fun forScale(msPerDp: Float, minGapDp: Float = 7f, minLabelDp: Float = 64f): TickSteps =
            ladder.firstOrNull { it.minorMs / msPerDp >= minGapDp && it.labelMs / msPerDp >= minLabelDp } ?: ladder.last()
    }
}
