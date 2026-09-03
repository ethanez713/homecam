package com.homecam.app.detect

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A microphone stream independent of the WebRTC audio track (Phase 6.1), so sound
 * detection works with nobody watching. It never starts unless the caller has decided the
 * microphone setting allows it — a camera that listens after being told not to is a bug of
 * a different kind from a missed detection.
 *
 * Each window is delivered as a normalised [-1, 1] waveform plus its RMS level in dBFS.
 */
class AudioTap(
    private val windowSamples: Int,
    private val onWindow: (waveform: FloatArray, levelDb: Float, nowMs: Long) -> Unit,
) {
    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null

    val running: Boolean get() = record != null

    // The caller gates on RECORD_AUDIO having been granted and on the microphone setting.
    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(this) {
            if (record != null) return
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuffer <= 0) return
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING,
                maxOf(minBuffer, windowSamples * 2),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                Log.w(TAG, "microphone unavailable for the sound tap")
                return
            }
            recorder.startRecording()
            record = recorder
            thread = Thread { loop(recorder) }.apply { isDaemon = true; start() }
        }
    }

    private fun loop(recorder: AudioRecord) {
        val pcm = ShortArray(windowSamples)
        val wave = FloatArray(windowSamples)
        while (record != null) {
            var read = 0
            while (read < windowSamples && record != null) {
                val n = recorder.read(pcm, read, windowSamples - read)
                if (n <= 0) break
                read += n
            }
            if (read < windowSamples) continue
            var sumSquares = 0.0
            for (i in 0 until windowSamples) {
                val v = pcm[i] / 32768f
                wave[i] = v
                sumSquares += v.toDouble() * v
            }
            val rms = sqrt(sumSquares / windowSamples)
            val db = if (rms > 0) (20 * log10(rms)).toFloat() else SILENCE_DB
            onWindow(wave.copyOf(), db, System.currentTimeMillis())
        }
    }

    fun stop() {
        synchronized(this) {
            val recorder = record ?: return
            record = null
            thread?.interrupt()
            thread = null
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    private companion object {
        const val TAG = "AudioTap"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val SILENCE_DB = -160f
    }
}
