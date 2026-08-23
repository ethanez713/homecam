package com.homecam.core.detect

import com.homecam.proto.DetectionMode
import com.homecam.proto.DetectionSchedule
import com.homecam.proto.DetectionZone
import com.homecam.proto.Point

/** A detection as it comes out of the model, in normalised frame coordinates. */
data class Detection(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centreX: Float get() = (left + right) / 2
    val centreY: Float get() = (top + bottom) / 2
}

/**
 * Decides whether detection should be running at all, and which detections count. Kept
 * here rather than beside the model because it is pure decision-making with awkward edge
 * cases — midnight-crossing windows, degenerate zones — and those deserve tests.
 */
object DetectionPolicy {

    /** COCO has the first three classes almost directly and does not have the fourth. */
    private val CATEGORIES: Map<String, String> = buildMap {
        put("person", "person")
        listOf("bicycle", "car", "motorcycle", "bus", "truck", "train", "boat")
            .forEach { put(it, "vehicle") }
        listOf("cat", "dog", "bird", "horse", "sheep", "cow").forEach { put(it, "pet") }
        // Approximate: a cardboard box is not a COCO class. See models/README.md.
        listOf("suitcase", "backpack", "handbag").forEach { put(it, "package") }
    }

    fun category(cocoLabel: String): String? = CATEGORIES[cocoLabel.lowercase()]

    fun isEnabled(mode: DetectionMode, category: String): Boolean = when (category) {
        "person" -> mode.person
        "vehicle" -> mode.vehicle
        "pet" -> mode.pet
        "package" -> mode.getPackage()
        else -> false
    }

    /**
     * [minuteOfDay] is local time, [dayOfWeek] is 0 for Monday.
     *
     * A window whose end is before its start wraps past midnight, which is the normal way
     * to say "overnight" and the case a naive range check gets silently wrong.
     */
    fun isWithinSchedule(schedule: DetectionSchedule, minuteOfDay: Int, dayOfWeek: Int): Boolean {
        if (!schedule.enabled || schedule.windowsCount == 0) return true
        return schedule.windowsList.any { window ->
            val today = window.daysMask and (1 shl dayOfWeek) != 0
            val yesterday = window.daysMask and (1 shl ((dayOfWeek + 6) % 7)) != 0
            val start = window.startMinute.toInt()
            val end = window.endMinute.toInt()
            if (start <= end) today && minuteOfDay >= start && minuteOfDay < end
            // The wrapped half belongs to the day the window *started* on.
            else (today && minuteOfDay >= start) || (yesterday && minuteOfDay < end)
        }
    }

    /**
     * A detection counts when its centre is inside the zone. Testing the centre rather
     * than any overlap is what makes a zone mean "things that are here" instead of
     * "things large enough to reach here from outside".
     */
    fun isWithinZone(zone: DetectionZone, detection: Detection): Boolean {
        if (!zone.enabled || zone.pointsCount < 3) return true
        return contains(zone.pointsList, detection.centreX, detection.centreY)
    }

    private fun contains(polygon: List<Point>, x: Float, y: Float): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > y) != (b.y > y) &&
                x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    fun accepts(
        mode: DetectionMode,
        zone: DetectionZone,
        detection: Detection,
    ): String? {
        val category = category(detection.label) ?: return null
        if (!isEnabled(mode, category)) return null
        if (detection.confidence < mode.minConfidence) return null
        if (!isWithinZone(zone, detection)) return null
        return category
    }
}
