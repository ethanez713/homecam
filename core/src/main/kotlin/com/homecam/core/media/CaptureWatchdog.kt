package com.homecam.core.media

/**
 * Decides when a running capture has gone blind, and when it has come back.
 *
 * A foreground service started from the background is allowed to keep running while
 * Android denies it the camera. Nothing fails loudly: the pipeline stays "started" and
 * the frame count stops moving. Silence for [blindAfterMs] while capture is running is
 * that denial. The service then tries to recover; if frames are still absent
 * [escalateAfterMs] after [noteRecoveryAttempt], the attempt did not work.
 *
 * A stopped capture is neither blind nor recovered. Stopping is intentional, and a gap
 * while stopped is not silence — the window opens again when capture is running.
 * [Sample.nowMs] is only ever compared with other values from the same clock, and
 * [Sample.frameCount] only ever increases.
 */
class CaptureWatchdog(
    private val blindAfterMs: Long = BLIND_AFTER_MS,
    private val escalateAfterMs: Long = ESCALATE_AFTER_MS,
) {
    enum class Signal { BECAME_BLIND, ESCALATE, RECOVERED }

    data class Sample(val nowMs: Long, val running: Boolean, val frameCount: Long)

    data class Observation(val signal: Signal?, val framesPerSecond: Float)

    private var blind = false
    private var wasRunning = false
    private var hasSample = false
    private var lastCount = 0L
    private var lastFrameAtMs = 0L
    private var lastSampleAtMs = 0L
    private var attemptAtMs: Long? = null
    private var escalated = false

    val isBlind: Boolean get() = blind

    /** The overlay recovery was launched. A second note does not move the deadline. */
    fun noteRecoveryAttempt(nowMs: Long) {
        if (!blind || attemptAtMs != null) return
        attemptAtMs = nowMs
    }

    fun observe(sample: Sample): Observation {
        val framesAdvanced = hasSample && sample.frameCount > lastCount
        val elapsed = if (hasSample) sample.nowMs - lastSampleAtMs else 0L
        val fps = if (sample.running && elapsed > 0L) {
            (sample.frameCount - lastCount).coerceAtLeast(0L) * 1000f / elapsed
        } else {
            0f
        }

        val signal = if (!sample.running) {
            null
        } else {
            val resumed = !wasRunning
            // Starting (or starting again) is not a frame, but it is not silence either.
            // A blind capture keeps its clock: the restart has to produce a frame.
            if ((resumed && !blind) || framesAdvanced) lastFrameAtMs = sample.nowMs
            when {
                framesAdvanced && blind -> {
                    blind = false
                    attemptAtMs = null
                    escalated = false
                    Signal.RECOVERED
                }
                !blind && hasSample && !resumed && sample.nowMs - lastFrameAtMs >= blindAfterMs -> {
                    blind = true
                    Signal.BECAME_BLIND
                }
                blind && !escalated && attemptAtMs != null &&
                    sample.nowMs - attemptAtMs!! >= escalateAfterMs -> {
                    escalated = true
                    Signal.ESCALATE
                }
                else -> null
            }
        }

        wasRunning = sample.running
        hasSample = true
        lastCount = sample.frameCount
        lastSampleAtMs = sample.nowMs
        return Observation(signal, fps)
    }

    private companion object {
        const val BLIND_AFTER_MS = 15_000L
        const val ESCALATE_AFTER_MS = 15_000L
    }
}
