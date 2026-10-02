package com.homecam.core.detect

/**
 * One alert per appearance, not one per detector pass. A category alerts when it has not
 * been seen for [quietMs]; every sighting restarts that wait, so something that stays in
 * view stays silent however long it stays, and alerts again only after it has been gone.
 */
class AlertThrottle(private val quietMs: Long = DEFAULT_QUIET_MS) {

    private val lastSeenAtMs = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldAlert(categories: Collection<String>, nowMs: Long): Boolean {
        val appeared = categories.any { category ->
            lastSeenAtMs[category]?.let { nowMs - it >= quietMs } ?: true
        }
        categories.forEach { lastSeenAtMs[it] = nowMs }
        return appeared
    }

    companion object {
        const val DEFAULT_QUIET_MS = 120_000L
    }
}
