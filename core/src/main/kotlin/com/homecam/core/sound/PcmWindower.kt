package com.homecam.core.sound

/**
 * Turns the interleaved 16-bit little-endian PCM that WebRTC captures (typically 48 kHz)
 * into the fixed-size mono 16 kHz windows sound detection consumes. Whole-ratio rates are
 * box-averaged, which is also the anti-alias filter; any other rate falls back to linear
 * interpolation. Not thread-safe: feed it from one thread.
 */
class PcmWindower(
    private val windowSamples: Int,
    private val outRate: Int = OUT_RATE,
    private val onWindow: (waveform: FloatArray, levelDb: Float) -> Unit,
) {
    private val window = FloatArray(windowSamples)
    private var filled = 0

    private var boxSum = 0.0
    private var boxCount = 0

    private var interpPrev = 0f
    private var interpPos = 0.0

    fun push(pcm: ByteArray, length: Int, inRate: Int, channels: Int) {
        if (inRate <= 0 || channels <= 0) return
        val frames = length / (2 * channels)
        val wholeRatio = inRate % outRate == 0
        val ratio = inRate / outRate
        for (f in 0 until frames) {
            var mono = 0f
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                mono += ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort() / 32768f
            }
            mono /= channels
            if (wholeRatio) {
                boxSum += mono
                if (++boxCount == ratio) {
                    emit((boxSum / ratio).toFloat())
                    boxSum = 0.0
                    boxCount = 0
                }
            } else {
                val step = inRate.toDouble() / outRate
                while (interpPos < 1.0) {
                    emit((interpPrev + (mono - interpPrev) * interpPos).toFloat())
                    interpPos += step
                }
                interpPos -= 1.0
                interpPrev = mono
            }
        }
    }

    fun reset() {
        filled = 0
        boxSum = 0.0
        boxCount = 0
        interpPrev = 0f
        interpPos = 0.0
    }

    private fun emit(sample: Float) {
        window[filled++] = sample
        if (filled < windowSamples) return
        filled = 0
        var sumSquares = 0.0
        for (v in window) sumSquares += v.toDouble() * v
        val rms = kotlin.math.sqrt(sumSquares / windowSamples)
        val db = if (rms > 0) (20 * kotlin.math.log10(rms)).toFloat() else SILENCE_DB
        onWindow(window.copyOf(), db)
    }

    companion object {
        const val OUT_RATE = 16_000
        const val SILENCE_DB = -160f
    }
}
