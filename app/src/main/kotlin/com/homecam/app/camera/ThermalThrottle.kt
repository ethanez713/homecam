package com.homecam.app.camera

import com.homecam.app.media.CameraMediaSession
import com.homecam.app.media.ContinuousCapture
import com.homecam.core.health.HealthSample
import com.homecam.core.health.HealthState

fun interface ThrottleStepListener {
    fun onStep(step: String, engaged: Boolean, state: HealthState, reading: HealthSample)
}

/**
 * Applies the thermal throttle and undoes it. Recording mode is not an input: the cap
 * is the only thing that changes what is kept.
 *
 * [listener] is told on each engage and release. This class does not log them.
 */
class ThermalThrottle(
    private val setCaptureThrottled: (Boolean) -> Unit,
    private val setDetectorThrottled: (Boolean) -> Unit,
    private val live: LiveSessionThrottle,
    private val screen: ScreenDimmer,
    var listener: ThrottleStepListener = ThrottleStepListener { _, _, _, _ -> },
) {
    private val lock = Any()
    private var engaged = false

    val bitrateCapBps: Int get() = live.bitrateCapBps

    fun onHealth(state: HealthState, reading: HealthSample) {
        val steps = synchronized(lock) {
            if (state.throttled == engaged) return
            engaged = state.throttled
            if (state.throttled) engageEffects() else releaseEffects()
        }
        steps.forEach { (step, on) -> listener.onStep(step, on, state, reading) }
    }

    fun onSessionOpen() = synchronized(lock) { live.onSessionOpen() }

    fun onSessionClose() = synchronized(lock) { live.onSessionClose() }

    /** True when a live session's resolution is being held down, so [next] is remembered. */
    fun offerFormat(next: ContinuousCapture.Format): Boolean = synchronized(lock) { live.offerFormat(next) }

    /** Service teardown. Restores the device without a throttle event: there is no reading. */
    fun forceRelease() {
        synchronized(lock) {
            engaged = false
            setCaptureThrottled(false)
            setDetectorThrottled(false)
            live.setThrottled(false)
        }
        screen.close()
    }

    private fun engageEffects(): List<Pair<String, Boolean>> {
        setCaptureThrottled(true)
        setDetectorThrottled(true)
        live.setThrottled(true)
        screen.engage()
        return STEPS.map { it to true }
    }

    private fun releaseEffects(): List<Pair<String, Boolean>> {
        screen.release()
        live.setThrottled(false)
        setDetectorThrottled(false)
        setCaptureThrottled(false)
        return STEPS.asReversed().map { it to false }
    }

    companion object {
        const val STEP_FPS = "fps"
        const val STEP_LIVE_BITRATE = "live_bitrate"
        const val STEP_SCREEN = "screen"

        private val STEPS = listOf(STEP_FPS, STEP_LIVE_BITRATE, STEP_SCREEN)
    }
}

/**
 * Lowers the live bitrate cap whenever throttling is on, and the capture resolution
 * only while a session is actually open. The resolution is the shared capture, so it
 * is put back when the session ends even if the phone is still hot.
 */
class LiveSessionThrottle(
    private val session: () -> CameraMediaSession?,
    private val format: () -> ContinuousCapture.Format,
    private val applyFormat: (ContinuousCapture.Format) -> Unit,
) {
    private var throttled = false
    private var restore: ContinuousCapture.Format? = null

    val bitrateCapBps: Int
        get() = if (throttled) CameraMediaSession.THROTTLED_MAX_BITRATE_BPS else CameraMediaSession.MAX_BITRATE_BPS

    fun setThrottled(on: Boolean) {
        throttled = on
        session()?.setBitrateCap(bitrateCapBps)
        if (on) lowerResolution() else restoreResolution()
    }

    fun onSessionOpen() {
        if (!throttled) return
        session()?.setBitrateCap(bitrateCapBps)
        lowerResolution()
    }

    fun onSessionClose() {
        restoreResolution()
    }

    fun offerFormat(next: ContinuousCapture.Format): Boolean {
        if (!throttled || session() == null) return false
        restore = next
        return true
    }

    private fun lowerResolution() {
        if (session() == null) return
        val current = format()
        if (current.width <= THROTTLE_WIDTH && current.height <= THROTTLE_HEIGHT) return
        if (restore == null) restore = current
        val next = ContinuousCapture.Format(THROTTLE_WIDTH, THROTTLE_HEIGHT, current.framerate)
        if (next != current) applyFormat(next)
    }

    private fun restoreResolution() {
        val next = restore ?: return
        restore = null
        if (next != format()) applyFormat(next)
    }

    companion object {
        /** The idle tier named for an unattended capture. A live session is the extra encoder. */
        const val THROTTLE_WIDTH = 1280
        const val THROTTLE_HEIGHT = 720
    }
}
