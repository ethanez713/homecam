package com.homecam.app.camera

import android.util.Log
import com.homecam.app.AppGraph
import com.homecam.app.data.ClipStore
import com.homecam.app.media.ClipRecorder
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.WebRtc
import com.homecam.proto.AuditEvent
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

    init {
        recorder.onClipWritten = { written ->
            graph.scope.launch {
                val entity = clips.index(written)
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
        if (armed) beginClip() else if (manualUntilMs <= System.currentTimeMillis()) endClip()
    }

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

    private fun shouldKeepRecording() =
        armed || manualUntilMs > System.currentTimeMillis()

    private fun beginClip() {
        if (recorder.recording) return
        recorder.beginClip(UUID.randomUUID().toString(), System.currentTimeMillis())
    }

    private fun endClip() {
        recorder.endClip()
    }

    private companion object { const val TAG = "Recorder" }
}
