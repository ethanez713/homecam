package com.homecam.app.camera

import android.util.Log
import com.homecam.app.AppGraph
import com.homecam.app.data.ClipStore
import com.homecam.app.media.ClipRecorder
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.WebRtc
import com.homecam.app.detect.DetectionPipeline
import com.homecam.proto.AuditEvent
import com.homecam.proto.DetectionFrame
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Turns the encoder's output into indexed, pruned clips.
 *
 * While armed the camera records in segments rather than one endless file: a clip that
 * rolls over every couple of minutes is one that retention can delete, that a viewer can
 * scrub to, and whose loss to a crash costs minutes rather than days. Detection (M7)
 * replaces the "always, while armed" trigger with "when something happened", and nothing
 * else here changes.
 */
class Recorder(
    private val graph: AppGraph,
    private val capture: ContinuousCapture,
    val clips: ClipStore,
) {
    private val recorder = ClipRecorder(clips.directory)

    @Volatile private var armed = false
    @Volatile private var manualUntilMs = 0L
    @Volatile private var detectionUntilMs = 0L

    /** Labels seen during the clip currently open, so the index says what is in it. */
    private val labelsInClip = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        recorder.onClipWritten = { written ->
            val labels = labelsInClip.toList()
            labelsInClip.clear()
            graph.scope.launch {
                val entity = clips.index(written, labels)
                Log.i(TAG, "clip ${entity.id} ${entity.durationMs}ms ${entity.sizeBytes}B")
                clips.prune(graph.settings.current.retention)
                if (shouldKeepRecording()) beginClip()
            }
        }
    }

    fun start() {
        recorder.start(WebRtc.eglBase.eglBaseContext)
        capture.addSink(recorder)
    }

    fun stop() {
        capture.removeSink(recorder)
        recorder.stop()
    }

    fun setArmed(armed: Boolean) {
        this.armed = armed
        if (!armed && manualUntilMs <= System.currentTimeMillis()) endClip()
    }

    /** Called on a timer so a clip closes once its detection tail has run out. */
    fun tick() {
        if (recorder.recording && !shouldKeepRecording()) endClip()
    }

    /**
     * A detection extends the clip rather than starting a new one per frame — one person
     * walking past should be one clip, not thirty.
     */
    fun onDetection(detections: List<DetectionPipeline.LabelledDetection>, nowMs: Long) {
        detectionUntilMs = nowMs + DETECTION_TAIL_MS
        detections.forEach { labelsInClip += it.category }
        beginClip()

        if (detections.isNotEmpty()) {
            recorder.writeDetections(frameOf(detections, nowMs).toByteArray(), nowMs)
            graph.scope.launch {
                graph.audit.record(
                    AuditEvent.Kind.DETECTION,
                    detail = detections.joinToString(", ") {
                        "%s %.0f%%".format(it.category, it.detection.confidence * 100)
                    },
                )
            }
        }
    }

    private fun frameOf(detections: List<DetectionPipeline.LabelledDetection>, nowMs: Long) =
        DetectionFrame.newBuilder()
            .setAtMs(nowMs)
            .addAllBoxes(
                detections.map {
                    DetectionFrame.Box.newBuilder()
                        .setLabel(it.category)
                        .setConfidence(it.detection.confidence)
                        .setLeft(it.detection.left)
                        .setTop(it.detection.top)
                        .setRight(it.detection.right)
                        .setBottom(it.detection.bottom)
                        .build()
                }
            )
            .build()

    /** A viewer asking for a clip right now, independent of arming. */
    fun recordManually(seconds: Int) {
        manualUntilMs = System.currentTimeMillis() + seconds.coerceIn(5, 300) * 1000L
        beginClip()
        graph.scope.launch {
            graph.audit.record(AuditEvent.Kind.SETTINGS_CHANGED, detail = "manual record ${seconds}s")
        }
    }

    fun stopManual() {
        manualUntilMs = 0
        if (!armed) endClip()
    }

    val recording: Boolean get() = recorder.recording

    /**
     * While armed the camera records on *detection*, not continuously. Continuous
     * recording would fill the retention budget with an empty hallway and push out the
     * minute that mattered.
     */
    private fun shouldKeepRecording(): Boolean {
        val now = System.currentTimeMillis()
        return manualUntilMs > now || (armed && detectionUntilMs > now)
    }

    private fun beginClip() {
        if (recorder.recording) return
        recorder.beginClip(UUID.randomUUID().toString(), System.currentTimeMillis())
    }

    private fun endClip() {
        recorder.endClip()
    }

    private companion object {
        const val TAG = "Recorder"
        // Long enough that someone pausing on the doorstep stays in one clip.
        const val DETECTION_TAIL_MS = 8_000L
    }
}
