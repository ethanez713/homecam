package com.homecam.app.detect

import android.content.Context
import com.homecam.core.detect.DetectionPolicy
import com.homecam.core.sound.SoundClasses
import com.homecam.core.diagnostics.AudioListen
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
    private val onEvent: (category: String, levelDb: Float, push: Boolean) -> Unit,
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

    var onListen: (feed: String, started: Boolean, reason: String) -> Unit = { _, _, _ -> }

    /** The decibel gate just opened, before classification and before any clip. */
    var onSound: () -> Unit = {}

    private val tap = AudioTap(windowSamples) { waveform, levelDb, nowMs ->
        onWindow(waveform, levelDb, nowMs)
    }

    // While a viewer is live, WebRTC's VOICE_COMMUNICATION recorder holds the microphone and
    // Android silences the tap, so detection listens to WebRTC's own capture instead.
    @Volatile private var live = false
    @Volatile private var liveFeed = false
    private val liveWindower = PcmWindower(windowSamples) { waveform, levelDb ->
        onWindow(waveform, levelDb, System.currentTimeMillis())
    }

    /** Starts or stops the microphone tap to match the current settings. Call on any change. */
    fun sync() = apply(AudioListen.ENABLED)

    /** Switches between the tap and WebRTC's capture as a session starts and ends. */
    fun setLive(active: Boolean) {
        live = active
        liveWindower.reset()
        apply(AudioListen.VIEWER_LIVE)
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
        if (tap.running) {
            tap.stop()
            onListen(AudioListen.TAP, false, AudioListen.SERVICE_STOP)
        }
        if (liveFeed) {
            liveFeed = false
            onListen(AudioListen.LIVE, false, AudioListen.SERVICE_STOP)
        }
        synchronized(inference) {
            closed = true
            classifier?.close()
        }
    }

    private fun apply(trigger: String) {
        val snapshot = settings()
        val shouldListen = shouldListen(snapshot)
        val reason = when {
            !snapshot.microphone -> AudioListen.MIC_OFF
            !snapshot.soundDetection.enabled -> AudioListen.SOUND_DETECTION_OFF
            else -> trigger
        }
        val tapWanted = shouldListen && !live
        val liveWanted = shouldListen && live
        if (tapWanted && !tap.running) {
            tap.start()
            if (tap.running) onListen(AudioListen.TAP, true, reason)
        } else if (!tapWanted && tap.running) {
            tap.stop()
            onListen(AudioListen.TAP, false, reason)
        }
        if (liveWanted != liveFeed) {
            liveFeed = liveWanted
            onListen(AudioListen.LIVE, liveWanted, reason)
        }
        if (!shouldListen) gate.reset()
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

        onSound()
        val wanted = SoundClasses.pushSelection(
            snapshot.soundRecognition.classesList.takeIf { snapshot.hasSoundRecognition() },
        )
        val recognised = synchronized(inference) {
            if (closed) null else classifier?.classify(waveform, wanted)?.category
        }
        val push = SoundClasses.pushCategory(classifierAvailable, recognised)
        onEvent(push ?: SoundClasses.GENERIC, levelDb, push != null)
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
    }
}
