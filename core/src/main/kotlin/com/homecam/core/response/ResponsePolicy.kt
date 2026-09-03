package com.homecam.core.response

/**
 * The two decisions Phase 8 makes that are worth testing away from Android: when the
 * detection reminder is due, and when the automatic spotlight should engage. Both are pure
 * so the device wiring is only plumbing.
 */
object ResponsePolicy {

    /**
     * A reminder is due when it is enabled, the camera is armed (a reminder while disarmed
     * would be a lie), and at least [intervalMinutes] have passed since the last one.
     * [lastRemindedMs] of 0 means none has been sent, so the first is due immediately.
     */
    fun reminderDue(
        enabled: Boolean,
        armed: Boolean,
        intervalMinutes: Int,
        lastRemindedMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!enabled || !armed || intervalMinutes <= 0) return false
        return nowMs - lastRemindedMs >= intervalMinutes * 60_000L
    }

    /**
     * The automatic spotlight engages only when enabled, the phone actually has a flash,
     * and the scene is dark enough to need it — a torch that flicks on in daylight on every
     * detection is a bug, not a feature.
     */
    fun autoSpotlightEngages(enabled: Boolean, hasFlash: Boolean, isDark: Boolean): Boolean =
        enabled && hasFlash && isDark

    /** Mean luma (0..255) below which the scene counts as dark. */
    const val DARK_LUMA_THRESHOLD = 50f
}
