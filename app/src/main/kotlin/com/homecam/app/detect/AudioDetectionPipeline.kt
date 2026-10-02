package com.homecam.app.detect

import android.content.Context
import com.homecam.core.detect.DetectionPolicy
import com.homecam.core.sound.PcmWindower
import com.homecam.core.sound.SoundGate
import com.homecam.proto.SettingsSnapshot
import java.time.Instant
import java.time.ZoneId
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The audio counterpart to [DetectionPipeline] (Phase 6). It listens only while the mic
 * setting and sound detection are both on, fires on a decibel threshold with hysteresis,
 * and — when classification is on — labels the event with a recognised sound class instead
 * of a generic one. It reuses the vision detection schedule: a camera that ignores motion
 * at 3pm should ignore sound at 3pm too.
 *
 * The two detectors stay independent, as they are in Alfred; this owns its own capture and
 * shares nothing with the video path.
 */
class AudioDetectionPipeline(
    context: Context,
    private val settings: () -> SettingsSnapshot,
    private val onEvent: (category: String, levelDb: Float) -> Unit,
) {
    private val classifier = YamnetClassifier.create(context)
    private val windowSamples = classifier?.windowSamples ?: SECOND_SAMPLES

    private val inference = Any()
    @Volatile private var closed = false
    @Volatile private var gate = SoundGate(DEFAULT_THRESHOLD_DB)
    @Volatile private var gateThreshold = DEFAULT_THRESHOLD_DB

    val classifierAvailable: Boolean get() = classifier != null

    /** Whether a microphone path is actually feeding detection — the privacy check for GATE-E. */
    val listening: Boolean get() = tap.running || (live && shouldListen(settings()))

    private val tap = AudioTap(windowSamples) { waveform, levelDb, nowMs ->
        onWindow(waveform, levelDb, nowMs)
    }

    // While a viewer is live, WebRTC's VOICE_COMMUNICATION recorder holds the microphone and
    // Android silences the tap, so detection listens to WebRTC's own capture instead.
    @Volatile private var live = false
    private val liveWindower = PcmWindower(windowSamples) { waveform, levelDb ->
        onWindow(waveform, levelDb, System.currentTimeMillis())
    }

    /** Starts or stops the microphone tap to match the current settings. Call on any change. */
    fun sync() {
        val shouldListen = shouldListen(settings())
        val tapWanted = shouldListen && !live
        if (tapWanted && !tap.running) tap.start()
        else if (!tapWanted && tap.running) tap.stop()
        if (!shouldListen) gate.reset()
    }

    /** Switches between the tap and WebRTC's capture as a session starts and ends. */
    fun setLive(active: Boolean) {
        live = active
        liveWindower.reset()
        sync()
    }

    /** Called on WebRTC's audio thread with whatever its recorder captured. */
    fun onLiveSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        if (!live || !shouldListen(settings())) return
        if (samples.audioFormat != android.media.AudioFormat.ENCODING_PCM_16BIT) return
        liveWindower.push(samples.data, samples.data.size, samples.sampleRate, samples.channelCount)
    }

    private fun shouldListen(snapshot: SettingsSnapshot) =
        snapshot.microphone && snapshot.soundDetection.enabled

    /** The tap thread may be mid-classification; see [DetectionPipeline.close]. */
    fun close() {
        tap.stop()
        synchronized(inference) {
            closed = true
            classifier?.close()
        }
    }

    private fun onWindow(waveform: FloatArray, levelDb: Float, nowMs: Long) {
        val snapshot = settings()
        if (!snapshot.microphone || !snapshot.soundDetection.enabled) return
        // Armed gates acting on sound just as it gates acting on vision — a disarmed camera
        // may still capture for the level meter, but it does not record or alert.
        if (!snapshot.armed || !withinSchedule(snapshot, nowMs)) {
            gate.reset()
            return
        }

        val threshold = snapshot.soundDetection.thresholdDb.takeIf { it != 0f } ?: DEFAULT_THRESHOLD_DB
        if (threshold != gateThreshold) {
            gate = SoundGate(threshold)
            gateThreshold = threshold
        }
        if (!gate.accept(levelDb, nowMs)) return

        onEvent(categorise(waveform, snapshot), levelDb)
    }

    private fun categorise(waveform: FloatArray, snapshot: SettingsSnapshot): String {
        val selected = snapshot.soundRecognition.classesList
        if (selected.isEmpty()) return GENERIC
        val event = synchronized(inference) { if (closed) null else classifier?.classify(waveform) }
            ?: return GENERIC
        return if (event.category in selected) event.category else GENERIC
    }

    private fun withinSchedule(snapshot: SettingsSnapshot, nowMs: Long): Boolean {
        val local = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
        return DetectionPolicy.isWithinSchedule(
            snapshot.detectionSchedule,
            minuteOfDay = local.hour * 60 + local.minute,
            dayOfWeek = local.dayOfWeek.value - 1,
        )
    }

    private companion object {
        const val SECOND_SAMPLES = 16_000
        const val DEFAULT_THRESHOLD_DB = -24f
        const val GENERIC = "sound"
    }
}
