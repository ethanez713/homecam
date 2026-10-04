package com.homecam.core.media

import com.homecam.core.diagnostics.PowerSavingReason

/**
 * When capture runs at the idle rate, and what brings it back.
 *
 * Engaging is a tick decision: the scene has to have been idle for [QUIET_MS].
 * Releasing is not. A trigger calls [release] at once, and the next tick must not
 * undo it before the quiet period has run again. Thermal throttle is a separate
 * hold on the idle rate; this class never clears it.
 */
class PowerSaving(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val setSaving: (Boolean) -> Boolean,
    private val log: (engaged: Boolean, reason: String) -> Unit,
    private val quietMs: Long = QUIET_MS,
) {
    private val lock = Any()
    private var engaged = false

    /** When the current idle stretch began. Null while a viewer or a clip is in progress. */
    private var idleSinceMs: Long? = nowMs()

    val isEngaged: Boolean get() = synchronized(lock) { engaged }

    /** Drops to the idle rate once the scene has been idle for [quietMs]. Never releases. */
    fun tick(sceneIdle: Boolean) {
        synchronized(lock) {
            val now = nowMs()
            if (!sceneIdle) {
                idleSinceMs = null
                return
            }
            val since = idleSinceMs ?: now.also { idleSinceMs = it }
            if (engaged || now - since < quietMs) return
            engaged = true
            if (setSaving(true)) log(true, PowerSavingReason.IDLE) else engaged = false
        }
    }

    /**
     * Full rate now, for [reason]. Restarts the idle stretch even when capture was
     * already at full rate, so a motion or sound that produces no clip still holds
     * the full rate for [quietMs].
     */
    fun release(reason: String) {
        synchronized(lock) {
            idleSinceMs = nowMs()
            if (!engaged) return
            engaged = false
            if (setSaving(false)) log(false, reason)
        }
    }

    companion object {
        /**
         * How long nothing must be happening before the tick engages.
         * A release inside this window is not undone by the tick that follows it.
         */
        const val QUIET_MS = 2_000L
    }
}
