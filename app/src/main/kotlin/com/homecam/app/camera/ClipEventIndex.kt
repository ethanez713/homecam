package com.homecam.app.camera

import com.homecam.app.data.ClipEntity
import com.homecam.app.data.toProto
import com.homecam.app.detect.DetectionTrackReader
import com.homecam.core.media.DetectionSample
import com.homecam.core.media.EventSpan
import com.homecam.core.media.EventSpans
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where inside each clip something was seen, read from the clip's own metadata track.
 * A finished clip never changes, so its spans are read once; reading a track means
 * opening the file, which a timeline query over a week of clips would otherwise repeat.
 */
object ClipEventIndex {

    private val cache = ConcurrentHashMap<String, List<EventSpan>>()

    fun spans(clip: ClipEntity): List<EventSpan> {
        if (clip.labels.isBlank()) return emptyList()
        cache[clip.id]?.let { return it }
        if (cache.size >= MAX_CACHED) cache.clear()
        val samples = DetectionTrackReader.read(File(clip.path), clip.startedAtMs).map { (offsetMs, frame) ->
            DetectionSample(offsetMs, frame.boxesList.map { it.label }.toSet())
        }
        return EventSpans.forClip(clip.toProto(), samples).also { cache[clip.id] = it }
    }

    private const val MAX_CACHED = 5_000
}
