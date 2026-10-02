package com.homecam.app.detect

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.homecam.proto.DetectionFrame
import java.io.File
import java.nio.ByteBuffer

/**
 * Reads the boxes back out of a clip's own timed-metadata track. This is what makes the
 * clip self-describing: the viewer draws overlays straight from the file, with no sidecar
 * and nothing to ask the camera for.
 */
object DetectionTrackReader {

    private const val TAG = "DetectionTrackReader"
    private const val METADATA_MIME = "application/x-homecam-detections"
    private const val MAX_SAMPLE_BYTES = 64 * 1024

    /**
     * Keyed by milliseconds from [clipStartedAtMs], taken from each frame's own wall-clock
     * time. The track's sample times cannot place it: the fragmented muxer writes no
     * decode-time base, so they count from the track's first sample, not from the clip's.
     */
    fun read(file: File, clipStartedAtMs: Long): Map<Long, DetectionFrame> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == METADATA_MIME
            } ?: return emptyMap()

            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
            buildMap {
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size <= 0) break
                    val bytes = ByteArray(size).also { buffer.position(0); buffer.get(it) }
                    runCatching { DetectionFrame.parseFrom(bytes) }
                        .onSuccess { put(it.atMs - clipStartedAtMs, it) }
                    if (!extractor.advance()) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "no readable detection track in ${file.name}", e)
            emptyMap()
        } finally {
            extractor.release()
        }
    }

    /** The boxes to draw at [positionMs], or none if the nearest sample is stale. */
    fun at(frames: Map<Long, DetectionFrame>, positionMs: Long, toleranceMs: Long = 700): DetectionFrame? =
        frames.entries
            .filter { kotlin.math.abs(it.key - positionMs) <= toleranceMs }
            .minByOrNull { kotlin.math.abs(it.key - positionMs) }
            ?.value
}
