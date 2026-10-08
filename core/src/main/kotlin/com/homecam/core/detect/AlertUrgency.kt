package com.homecam.core.detect

import com.homecam.core.sound.SoundClasses

/**
 * Urgent means two independent signals agree: a person and a distress sound, or two
 * different distress sounds, within [WINDOW_MS] of each other in either order. One signal
 * alone (a lone shout, a person in view) is an ordinary alert. After one urgent alert the
 * next waits [COOLDOWN_MS], so a continuing incident does not spend the reserved budget.
 */
class AlertUrgency {

    private val lastSeenMs = mutableMapOf<String, Long>()
    private var lastUrgentMs: Long? = null

    /** True when this observation completes a coincidence outside the cooldown. */
    @Synchronized
    fun observe(categories: Collection<String>, nowMs: Long): Boolean {
        val relevant = categories.filter { it == PERSON || it in DISTRESS }
        if (relevant.isEmpty()) return false
        relevant.forEach { lastSeenMs[it] = nowMs }

        val recent = lastSeenMs.filterValues { nowMs - it <= WINDOW_MS }.keys
        val distress = recent.count { it in DISTRESS }
        val coincides = (PERSON in recent && distress >= 1) || distress >= 2
        if (!coincides) return false

        val last = lastUrgentMs
        if (last != null && nowMs - last < COOLDOWN_MS) return false
        lastUrgentMs = nowMs
        return true
    }

    companion object {
        const val WINDOW_MS = 60_000L
        const val COOLDOWN_MS = 600_000L
        const val PERSON = "person"
        val DISTRESS: Set<String> = setOf(SoundClasses.GLASS, SoundClasses.SHOUT, SoundClasses.ALARM)
    }
}
