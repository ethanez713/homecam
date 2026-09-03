package com.homecam.app.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * A loud two-tone alarm through the camera phone's own speaker (Phase 8.1). It plays on the
 * alarm stream so the phone's ring/media volume does not silence it, raises that stream to
 * full while it runs, and — the property that matters — stops itself after a timeout. A
 * siren that needs the app open to stop is a siren that gets the phone thrown in a drawer.
 *
 * The waveform is generated rather than shipped as an asset: two alternating tones are a
 * recognisable alarm and cost nothing to synthesise.
 */
class Siren(private val context: Context, private val raiseVolume: Boolean = true) {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var previousVolume: Int? = null

    val active: Boolean get() = track != null

    /** Idempotent: a second start while already running does not stack a second track. */
    @Synchronized
    fun start(seconds: Int = DEFAULT_SECONDS) {
        if (track != null) return
        val durationMs = seconds.coerceIn(1, MAX_SECONDS) * 1000L

        val audio = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(alarmPcm().size * 2)
            .build()

        val samples = alarmPcm()
        audio.write(samples, 0, samples.size)
        audio.setLoopPoints(0, samples.size, -1)
        raiseAlarmVolume()
        audio.play()
        track = audio

        thread = Thread {
            try {
                Thread.sleep(durationMs)
            } catch (e: InterruptedException) {
                // stopped early
            }
            stop()
        }.apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
        restoreAlarmVolume()
    }

    private fun raiseAlarmVolume() {
        if (!raiseVolume) return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        previousVolume = manager.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching {
            manager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                manager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        }.onFailure { Log.w(TAG, "could not raise alarm volume", it) }
    }

    private fun restoreAlarmVolume() {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        previousVolume?.let { runCatching { manager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        previousVolume = null
    }

    /** One second of an alternating two-tone alarm, looped by the track. */
    private fun alarmPcm(): ShortArray {
        val pattern = SAMPLE_RATE // 1s
        val out = ShortArray(pattern)
        for (i in out.indices) {
            val high = (i / (SAMPLE_RATE / 4)) % 2 == 0
            val freq = if (high) TONE_HIGH_HZ else TONE_LOW_HZ
            out[i] = (sin(2.0 * PI * freq * i / SAMPLE_RATE) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private companion object {
        const val TAG = "Siren"
        const val SAMPLE_RATE = 44_100
        const val TONE_HIGH_HZ = 1_000.0
        const val TONE_LOW_HZ = 800.0
        const val DEFAULT_SECONDS = 30
        const val MAX_SECONDS = 300
    }
}
