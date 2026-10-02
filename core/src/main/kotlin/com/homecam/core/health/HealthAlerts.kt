package com.homecam.core.health

data class HealthAlert(val label: String, val axis: Axis)

object HealthAlertLabels {
    const val PREFIX = "health:"
    const val RESUMED = "health:resumed"
    const val STORAGE_CRITICAL = "health:storage-critical"
    private const val PAUSED = "health:paused:"

    fun paused(axis: Axis) = PAUSED + axis.name.lowercase()

    fun isHealth(label: String) = label.startsWith(PREFIX)

    /** The notification body for a health label, or null when the label is not one of ours. */
    fun describe(label: String): String? = when {
        label == RESUMED -> "Continuous recording is back."
        label == STORAGE_CRITICAL -> "Storage is critically low. Recording has stopped."
        label.startsWith(PAUSED) -> Axis.entries.firstOrNull { it.name.lowercase() == label.removePrefix(PAUSED) }
            ?.let { "Recording is paused: ${HealthProtocol.describeCause(it)}." }
        else -> null
    }
}

/**
 * Decides which cap changes are worth a push. Only a cap change or a storage CRITICAL entry
 * speaks, never a WARN, and a pause that was announced less than [MIN_GAP_MS] ago on the same
 * axis stays quiet: a burst of alerts is how people learn to ignore them. A resume is only
 * announced when its pause was, so nobody is told about the end of something they never heard
 * begin.
 */
class HealthAlertPolicy {
    private val lastPauseAlertMs = mutableMapOf<Axis, Long>()
    private var announcedAxis: Axis? = null

    fun onChange(previous: HealthState, current: HealthState, nowMs: Long): List<HealthAlert> {
        val alerts = mutableListOf<HealthAlert>()
        val storageEnteredCritical =
            current.storage.level == Level.CRITICAL && previous.storage.level != Level.CRITICAL

        if (current.cap > previous.cap && current.capReason != null) {
            val axis = current.capReason
            val label = if (axis == Axis.STORAGE && storageEnteredCritical) HealthAlertLabels.STORAGE_CRITICAL
            else HealthAlertLabels.paused(axis)
            announce(axis, label, nowMs)?.let(alerts::add)
        } else if (storageEnteredCritical) {
            announce(Axis.STORAGE, HealthAlertLabels.STORAGE_CRITICAL, nowMs)?.let(alerts::add)
        }

        if (current.cap == Cap.NONE && previous.cap != Cap.NONE) {
            val axis = announcedAxis
            if (axis != null) alerts += HealthAlert(HealthAlertLabels.RESUMED, axis)
            announcedAxis = null
        }
        return alerts
    }

    private fun announce(axis: Axis, label: String, nowMs: Long): HealthAlert? {
        val last = lastPauseAlertMs[axis]
        if (last != null && nowMs - last < MIN_GAP_MS) return null
        lastPauseAlertMs[axis] = nowMs
        announcedAxis = axis
        return HealthAlert(label, axis)
    }

    companion object {
        const val MIN_GAP_MS = 30 * 60_000L
    }
}
