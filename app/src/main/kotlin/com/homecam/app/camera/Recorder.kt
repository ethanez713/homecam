package com.homecam.app.camera

import android.util.Log
import com.homecam.app.AppGraph
import com.homecam.app.data.ClipStore
import com.homecam.app.media.ClipRecorder
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.WebRtc
import com.homecam.app.detect.DetectionPipeline
import com.homecam.core.health.Cap
import com.homecam.core.health.HealthProtocol
import com.homecam.core.health.RecordingGate
import com.homecam.proto.AuditEvent
import com.homecam.proto.DetectionFrame
import com.homecam.proto.RecordingMode
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
    @Volatile private var mode = RecordingMode.DETECTION
    @Volatile private var cap = Cap.NONE
    @Volatile private var manualUntilMs = 0L
    @Volatile private var detectionUntilMs = 0L
    @Volatile private var pendingManualClipId: String? = null
    @Volatile private var prevSegmentEndMs = 0L

    /** Fired when a manual clip closes, so the requester can be told its id (Phase 4). */
    var onManualResult: ((ClipRecorder.Written) -> Unit)? = null

    /** Labels seen during the clip currently open, so the index says what is in it. */
    private val labelsInClip = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        recorder.nextSegmentId = { if (shouldKeepRecording()) UUID.randomUUID().toString() else null }
        recorder.onClipWritten = { written ->
            val labels = labelsInClip.toList()
            labelsInClip.clear()
            // The gap since the previous segment ended: near-zero under continuous recording,
            // and the first thing to check if the soak shows a hole in the record (GATE-C).
            val gapMs = if (prevSegmentEndMs == 0L) 0L else written.startedAtMs - prevSegmentEndMs
            prevSegmentEndMs = written.startedAtMs + written.durationMs
            if (written.id == pendingManualClipId) {
                pendingManualClipId = null
                onManualResult?.invoke(written)
            }
            graph.scope.launch {
                val entity = clips.index(written, labels)
                Log.i(TAG, "clip ${entity.id} ${entity.durationMs}ms ${entity.sizeBytes}B")
                graph.diagnostics.segment(
                    entity.id, entity.durationMs, entity.sizeBytes, gapMs, cap, effectiveMode,
                )
                graph.diagnostics.prune(clips.prune(graph.settings.current.retention))
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
        if (!shouldKeepRecording()) endClip()
    }

    /** In CONTINUOUS the recorder keeps a muxer open regardless of arming or detection. */
    fun setMode(mode: RecordingMode) {
        this.mode = mode
        if (!shouldKeepRecording()) endClip()
    }

    /**
     * Protection holds recording down without touching the setting: the camera that paused
     * itself at 3 a.m. must come back as the mode the user chose once the cause is gone, with
     * nobody there to re-enable it. Resuming is the next [tick] seeing the cap lifted.
     */
    fun setCap(cap: Cap) {
        this.cap = cap
        if (cap == Cap.NO_RECORDING) manualUntilMs = 0
        if (!shouldKeepRecording()) endClip()
    }

    val manualRecordingRefused: Boolean get() = cap == Cap.NO_RECORDING

    /**
     * Called on a timer so a clip closes once its detection tail has run out — and, in
     * CONTINUOUS, so recording starts (and resumes after a segment rollover) even with
     * nothing detected and nobody watching.
     */
    fun tick() {
        if (recorder.recording && !shouldKeepRecording()) endClip()
        else if (!recorder.recording && shouldKeepRecording()) beginClip()
    }

    /**
     * A detection extends the clip rather than starting a new one per frame — one person
     * walking past should be one clip, not thirty.
     */
    fun onDetection(detections: List<DetectionPipeline.LabelledDetection>, nowMs: Long) {
        // OFF keeps nothing, so a detection is not recorded — the alert path is separate
        // and still fires. In CONTINUOUS beginClip is a no-op on the already-open segment,
        // and the detection is labelled and written into it just the same.
        if (effectiveMode == RecordingMode.OFF) return
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

    /**
     * A sound event triggers a clip the same way a vision detection does (Phase 6.5), but
     * carries no bounding box — only a label, so the viewer can tell why the clip exists.
     */
    fun onSoundEvent(category: String, nowMs: Long) {
        if (effectiveMode == RecordingMode.OFF) return
        detectionUntilMs = nowMs + DETECTION_TAIL_MS
        labelsInClip += category
        beginClip()
        graph.scope.launch {
            graph.audit.record(AuditEvent.Kind.DETECTION, detail = "sound: $category")
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
        if (manualRecordingRefused) return
        manualUntilMs = System.currentTimeMillis() + seconds.coerceIn(5, 300) * 1000L
        beginClip()
        // The open clip — freshly begun, or the continuous segment already running — is
        // the one whose id the requester is told about when it closes.
        pendingManualClipId = recorder.currentClipId
        graph.scope.launch {
            graph.audit.record(AuditEvent.Kind.SETTINGS_CHANGED, detail = "manual record ${seconds}s")
        }
    }

    fun stopManual() {
        manualUntilMs = 0
        if (!armed) endClip()
    }

    val recording: Boolean get() = recorder.recording

    /** A detection's tail or a manual clip is running — as opposed to an idle continuous segment. */
    val eventInProgress: Boolean
        get() = System.currentTimeMillis().let { it < detectionUntilMs || it < manualUntilMs }

    /**
     * Why DETECTION is the default rather than CONTINUOUS: recording on detection keeps the
     * retention budget spent on the minutes something happened, instead of filling it with
     * an empty hallway that pushes out the minute that mattered. CONTINUOUS exists for when
     * a gap-free record is worth that cost, and retention is what makes it survivable — but
     * it is a choice the user makes, not the default. A manual request overrides the mode.
     */
    private fun shouldKeepRecording(): Boolean = RecordingGate.shouldKeepRecording(
        mode, cap, armed, System.currentTimeMillis(), manualUntilMs, detectionUntilMs,
    )

    private val effectiveMode: RecordingMode get() = HealthProtocol.effectiveMode(mode, cap)

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
