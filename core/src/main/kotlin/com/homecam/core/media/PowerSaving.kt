package com.homecam.core.media

import com.homecam.core.diagnostics.PowerSavingReason

/**
 * When capture runs at the idle rate, and what brings it back.
 *
 * Engaging is a tick decision: the scene has to have been idle for the current
 * quiet period, [QUIET_MS] until a short engagement lengthens it. A release
 * before the engagement has lasted [STABLE_ENGAGED_MS] doubles that period, up
 * to [QUIET_MS_CAP]. One that lasts at least [STABLE_ENGAGED_MS] puts it back
 * to [QUIET_MS]. Releasing is not a tick decision. A trigger calls [release]
 * at once, and the next tick must not undo it before the quiet period has run
 * again. A trigger that arrives while capture is already at full rate restarts
 * the idle stretch and does not change the period. Thermal throttle is a
 * separate hold on the idle rate; this class never clears it.
 */
class PowerSaving(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val setSaving: (Boolean) -> Boolean,
    private val log: (engaged: Boolean, reason: String) -> Unit,
    private val quietMs: Long = QUIET_MS,
) {
    private val lock = Any()
    private var engaged = false
    private var engagedAtMs: Long? = null
    private var currentQuietMs = quietMs

    /** When the current idle stretch began. Null while a viewer or a clip is in progress. */
    private var idleSinceMs: Long? = nowMs()

    val isEngaged: Boolean get() = synchronized(lock) { engaged }

    /** Drops to the idle rate once the scene has been idle for the current quiet period. Never releases. */
    fun tick(sceneIdle: Boolean) {
        synchronized(lock) {
            val now = nowMs()
            if (!sceneIdle) {
                idleSinceMs = null
                return
            }
            val since = idleSinceMs ?: now.also { idleSinceMs = it }
            if (engaged || now - since < currentQuietMs) return
            engaged = true
            if (setSaving(true)) {
                engagedAtMs = now
                log(true, PowerSavingReason.IDLE)
            } else {
                engaged = false
            }
        }
    }

    /**
     * Full rate now, for [reason]. Restarts the idle stretch even when capture was
     * already at full rate, so a motion or sound that produces no clip still holds
     * the full rate for the current quiet period.
     */
    fun release(reason: String) {
        synchronized(lock) {
            val now = nowMs()
            idleSinceMs = now
            if (!engaged) return
            val lasted = now - (engagedAtMs ?: now)
            currentQuietMs = if (lasted >= STABLE_ENGAGED_MS) {
                quietMs
            } else {
                (currentQuietMs * 2).coerceAtMost(QUIET_MS_CAP)
            }
            engaged = false
            engagedAtMs = null
            if (setSaving(false)) log(false, reason)
        }
    }

    companion object {
        /**
         * Quiet period before the first engagement, and after one that lasted
         * [STABLE_ENGAGED_MS]. A release inside the current period is not undone
         * by the tick that follows it.
         */
        const val QUIET_MS = 30_000L

        /** A shorter engagement doubles the next quiet period up to this. */
        const val QUIET_MS_CAP = 240_000L

        /** An engagement released at or after this long resets the quiet period to [QUIET_MS]. */
        const val STABLE_ENGAGED_MS = 300_000L
    }
}
