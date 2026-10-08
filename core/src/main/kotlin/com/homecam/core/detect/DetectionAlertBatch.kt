package com.homecam.core.detect

/**
 * Turns the detections of one moment into one alert. [AlertThrottle] decides that something
 * deserves an alert; this holds that alert for [HOLD_MS] so the person, the sound they made
 * and the second person arriving seconds later read as one notification instead of three.
 * [AlertUrgency] can open a batch the throttle would not, and marks it urgent.
 */
class DetectionAlertBatch(
    private val throttle: AlertThrottle = AlertThrottle(),
    private val urgency: AlertUrgency = AlertUrgency(),
) {

    class Batch(val labels: List<String>, val thumbnail: ByteArray?, val urgent: Boolean)

    private var counts: MutableMap<String, Int>? = null
    private var thumbnail: ByteArray? = null
    private var urgent = false

    /** True when this call opened a batch: the caller flushes it [HOLD_MS] later. */
    @Synchronized
    fun add(categories: Collection<String>, thumbnail: ByteArray?, nowMs: Long): Boolean {
        val alertable = throttle.shouldAlert(categories, nowMs)
        val isUrgent = urgency.observe(categories, nowMs)
        val open = counts
        if (open == null && !alertable && !isUrgent) return false
        if (isUrgent) urgent = true
        val target = open ?: LinkedHashMap<String, Int>().also { counts = it }
        categories.groupingBy { it }.eachCount().forEach { (category, count) ->
            target.merge(category, count, ::maxOf)
        }
        if (this.thumbnail == null) this.thumbnail = thumbnail
        return open == null
    }

    @Synchronized
    fun flush(): Batch? {
        val held = counts ?: return null
        val batch = Batch(held.flatMap { (category, count) -> List(count) { category } }, thumbnail, urgent)
        counts = null
        thumbnail = null
        urgent = false
        return batch
    }

    companion object {
        const val HOLD_MS = 8_000L
    }
}
