package com.homecam.core.detect

/**
 * Turns the detections of one moment into one alert. [AlertThrottle] decides that something
 * deserves an alert; this holds that alert for [HOLD_MS] so the person, the sound they made
 * and the second person arriving seconds later read as one notification instead of three.
 */
class DetectionAlertBatch(private val throttle: AlertThrottle = AlertThrottle()) {

    class Batch(val labels: List<String>, val thumbnail: ByteArray?)

    private var counts: MutableMap<String, Int>? = null
    private var thumbnail: ByteArray? = null

    /** True when this call opened a batch: the caller flushes it [HOLD_MS] later. */
    @Synchronized
    fun add(categories: Collection<String>, thumbnail: ByteArray?, nowMs: Long): Boolean {
        val deserves = throttle.shouldAlert(categories, nowMs)
        val open = counts
        if (open == null && !deserves) return false
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
        val batch = Batch(held.flatMap { (category, count) -> List(count) { category } }, thumbnail)
        counts = null
        thumbnail = null
        return batch
    }

    companion object {
        const val HOLD_MS = 8_000L
    }
}
