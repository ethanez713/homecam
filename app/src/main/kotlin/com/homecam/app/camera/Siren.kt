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
 * [AudioAttributes.USAGE_ALARM] is what silent mode and Do Not Disturb already let through
 * when alarms are permitted, which is Samsung's default. Nothing here takes
 * notification-policy access or changes the ringer or Do Not Disturb settings.
 *
 * The volume from before the raise is written down as well as remembered. A process kill
 * cannot run [stop]; [recoverInterrupted] puts that volume back on the next start.
 *
 * The waveform is generated rather than shipped as an asset: two alternating tones are a
 * recognisable alarm and cost nothing to synthesise.
 */
class Siren(private val context: Context, private val raiseVolume: Boolean = true) {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null

    /** Set only by the instance that raised the stream, so some other [stop] cannot clear a marker it does not own. */
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
        // Unity gain: full volume is the alarm stream at its maximum, not a ducked track.
        audio.setVolume(AudioTrack.getMaxVolume())
        raiseAlarmVolume()
        try {
            audio.play()
        } catch (e: Exception) {
            runCatching { audio.release() }
            restoreAlarmVolume()
            Log.w(TAG, "alarm track did not start", e)
            return
        }
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
        val playing = track
        try {
            playing?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            // Before [active] goes false, so a waiter that sees the siren end also sees the old volume.
            restoreAlarmVolume()
        } finally {
            track = null
        }
    }

    private fun raiseAlarmVolume() {
        if (!raiseVolume) return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        synchronized(volumeLock) {
            val prior = manager.getStreamVolume(AudioManager.STREAM_ALARM)
            previousVolume = prior
            // commit, not apply: the process can be killed as soon as the stream is raised.
            runCatching {
                val committed = sirenPrefs().edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putInt(KEY_VOLUME, prior)
                    .commit()
                if (!committed) Log.w(TAG, "could not persist siren volume")
            }.onFailure { Log.w(TAG, "could not persist siren volume", it) }
            runCatching {
                manager.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    manager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0,
                )
            }.onFailure { Log.w(TAG, "could not raise alarm volume", it) }
        }
    }

    private fun restoreAlarmVolume() {
        synchronized(volumeLock) {
            val prior = previousVolume ?: return
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (manager != null) {
                runCatching { manager.setStreamVolume(AudioManager.STREAM_ALARM, prior, 0) }
                    .onFailure { Log.w(TAG, "could not restore alarm volume", it) }
            } else {
                Log.w(TAG, "could not restore alarm volume")
            }
            previousVolume = null
            runCatching {
                sirenPrefs().edit().remove(KEY_ACTIVE).remove(KEY_VOLUME).commit()
            }.onFailure { Log.w(TAG, "could not clear the persisted siren volume", it) }
        }
    }

    private fun sirenPrefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    companion object {
        private const val TAG = "Siren"
        private const val SAMPLE_RATE = 44_100
        private const val TONE_HIGH_HZ = 1_000.0
        private const val TONE_LOW_HZ = 800.0
        private const val DEFAULT_SECONDS = 30
        private const val MAX_SECONDS = 300
        private const val PREFS = "homecam.siren"
        private const val KEY_ACTIVE = "active"
        private const val KEY_VOLUME = "volume"
        private val volumeLock = Any()

        /**
         * Puts [AudioManager.STREAM_ALARM] back when the process died while the siren was
         * sounding, and clears the marker. A clean [stop] has already cleared it.
         *
         * @return whether a siren was still marked active. The next service start records that.
         */
        fun recoverInterrupted(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            synchronized(volumeLock) {
                if (!prefs.getBoolean(KEY_ACTIVE, false)) return false
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (manager != null && prefs.contains(KEY_VOLUME)) {
                    val volume = prefs.getInt(KEY_VOLUME, manager.getStreamVolume(AudioManager.STREAM_ALARM))
                    runCatching { manager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
                        .onFailure { Log.w(TAG, "could not restore alarm volume after interruption", it) }
                } else {
                    Log.w(TAG, "siren was interrupted but its volume was not saved")
                }
                runCatching {
                    prefs.edit().remove(KEY_ACTIVE).remove(KEY_VOLUME).commit()
                }.onFailure { Log.w(TAG, "could not clear the persisted siren volume", it) }
                return true
            }
        }
    }
}
