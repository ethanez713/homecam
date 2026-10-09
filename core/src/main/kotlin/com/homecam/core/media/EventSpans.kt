package com.homecam.core.media

import com.homecam.proto.Clip

/** A stretch of time something was seen or heard, on the clock the clips are placed on. */
data class EventSpan(val clipId: String, val label: String, val startMs: Long, val endMs: Long)

/** One decoded sample of a clip's detection track: what was in frame, [offsetMs] into the clip. */
data class DetectionSample(val offsetMs: Long, val labels: Set<String>)

object EventSpans {

    /**
     * Samples closer together than [MERGE_GAP_MS] are one event, so a person walking through
     * is one mark rather than one per detector tick; each span runs [TAIL_MS] past its last
     * sample, because a detection says the thing was there until at least the next look.
     */
    fun fromSamples(clip: Clip, samples: List<DetectionSample>): List<EventSpan> {
        val byLabel = samples.flatMap { s -> s.labels.map { it to s.offsetMs } }.groupBy({ it.first }, { it.second })
        return byLabel.flatMap { (label, offsets) ->
            offsets.sorted().fold(mutableListOf<LongRange>()) { runs, at ->
                val last = runs.lastOrNull()
                if (last != null && at - last.last <= MERGE_GAP_MS) runs[runs.lastIndex] = last.first..at else runs += at..at
                runs
            }.map { run ->
                EventSpan(
                    clip.id,
                    label,
                    clip.startedAtMs + run.first.coerceIn(0, clip.durationMs.toLong()),
                    clip.startedAtMs + (run.last + TAIL_MS).coerceAtMost(clip.durationMs.toLong()),
                )
            }
        }.sortedBy { it.startMs }
    }

    /**
     * A label with no samples to place it (a sound has no boxes) marks the whole clip, as
     * does a clip whose track could not be read, so no event the clip list shows goes unmarked.
     */
    fun forClip(clip: Clip, samples: List<DetectionSample>): List<EventSpan> {
        val placed = fromSamples(clip, samples)
        val covered = placed.map { it.label }.toSet()
        val unplaced = clip.labelsList.filter { it !in covered }
            .map { EventSpan(clip.id, it, clip.startedAtMs, clip.startedAtMs + clip.durationMs) }
        return (placed + unplaced).sortedBy { it.startMs }
    }

    const val MERGE_GAP_MS = 3_000L
    const val TAIL_MS = 1_000L
}
