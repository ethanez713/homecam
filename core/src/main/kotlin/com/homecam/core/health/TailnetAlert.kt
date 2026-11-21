package com.homecam.core.health

enum class TailnetAlert(val label: String) {
    DOWN(HealthAlertLabels.TAILNET_DOWN),
    BACK(HealthAlertLabels.TAILNET_BACK),
}

/**
 * Whether a tailnet sample is worth a push. Silent until a tailnet has been seen at
 * least once. Down is announced after [GRACE_MS] of continuous absence, measured from
 * the first absent sample of that absence in this process, so Tailscale starting after
 * boot gets the grace and the clock is not kept across restarts. A down inside
 * [MIN_GAP_MS] of the previous one is dropped for that whole absence, and a return is
 * announced only when the down was — the same anti-burst rule as [HealthAlertPolicy].
 * [seen] and [announcedDown] are the caller's to keep: a reboot where Tailscale never
 * comes back still alerts, and a restart in the middle of an announced outage does not
 * send the down again.
 */
class TailnetAlertPolicy(
    seen: Boolean = false,
    announcedDown: Boolean = false,
) {
    var seen: Boolean = seen
        private set

    var announcedDown: Boolean = announcedDown
        private set

    private var absentSinceMs: Long? = null
    private var lastDownMs: Long? = null
    private var downDecided = false

    fun onSample(present: Boolean, nowMs: Long): TailnetAlert? {
        if (present) {
            seen = true
            absentSinceMs = null
            downDecided = false
            if (!announcedDown) return null
            announcedDown = false
            return TailnetAlert.BACK
        }
        val since = absentSinceMs
        if (since == null) {
            absentSinceMs = nowMs
            return null
        }
        if (!seen || announcedDown || downDecided) return null
        if (nowMs - since < GRACE_MS) return null
        // Before the gap check, so a suppressed absence is not announced when the gap ends.
        downDecided = true
        val previous = lastDownMs
        if (previous != null && nowMs - previous < MIN_GAP_MS) return null
        lastDownMs = nowMs
        announcedDown = true
        return TailnetAlert.DOWN
    }

    companion object {
        const val GRACE_MS = 5 * 60_000L
        const val MIN_GAP_MS = 30 * 60_000L
    }
}
