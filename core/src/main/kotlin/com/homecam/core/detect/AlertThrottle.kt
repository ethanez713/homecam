package com.homecam.core.detect

/**
 * One alert per visit, not one per detector pass. A category's visit lasts until it has
 * gone unseen for [quietMs], so something that stays in view, or leaves and comes straight
 * back, alerts once. The one exception is the first time a visit shows more than one of the
 * category at once, which alerts once more; the count moving after that says nothing new.
 */
class AlertThrottle(private val quietMs: Long = DEFAULT_QUIET_MS) {

    private class Visit(var lastSeenAtMs: Long, var severalAlerted: Boolean)

    private val visits = mutableMapOf<String, Visit>()

    /** [categories] holds one entry per thing detected, so a category repeated is several of it. */
    @Synchronized
    fun shouldAlert(categories: Collection<String>, nowMs: Long): Boolean {
        var alert = false
        categories.groupingBy { it }.eachCount().forEach { (category, count) ->
            val several = count > 1
            val visit = visits[category]
            if (visit == null || nowMs - visit.lastSeenAtMs >= quietMs) {
                visits[category] = Visit(nowMs, several)
                alert = true
            } else {
                if (several && !visit.severalAlerted) {
                    visit.severalAlerted = true
                    alert = true
                }
                visit.lastSeenAtMs = nowMs
            }
        }
        return alert
    }

    companion object {
        const val DEFAULT_QUIET_MS = 300_000L
    }
}
