package com.homecam.core.sound

/**
 * The decibel half of sound detection (Phase 6.2): fire once when the level rises above a
 * threshold, and do not fire again until it has fallen back down and a refractory window
 * has passed. Without that hysteresis a single bang — whose level wobbles around the
 * threshold for a moment, and which echoes — produces a burst of events instead of one.
 *
 * Pure and level-based so it works on a phone too weak to run audio classification, and so
 * it can be tested without a microphone.
 */
class SoundGate(
    private val thresholdDb: Float,
    /** How far the level must fall below the threshold before the gate can fire again. */
    private val releaseMarginDb: Float = DEFAULT_RELEASE_MARGIN_DB,
    /** Minimum quiet time after an event before another can fire. */
    private val refractoryMs: Long = DEFAULT_REFRACTORY_MS,
) {
    private var armed = true
    private var fired = false
    private var lastEventMs = 0L

    /** True on the single sample where a new sound event begins. */
    fun accept(levelDb: Float, nowMs: Long): Boolean {
        if (levelDb < thresholdDb - releaseMarginDb) armed = true
        val refractoryPassed = !fired || nowMs - lastEventMs >= refractoryMs
        if (armed && levelDb >= thresholdDb && refractoryPassed) {
            armed = false
            fired = true
            lastEventMs = nowMs
            return true
        }
        return false
    }

    fun reset() {
        armed = true
        fired = false
        lastEventMs = 0L
    }

    private companion object {
        const val DEFAULT_RELEASE_MARGIN_DB = 6f
        const val DEFAULT_REFRACTORY_MS = 4_000L
    }
}
