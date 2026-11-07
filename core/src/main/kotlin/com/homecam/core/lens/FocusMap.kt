package com.homecam.core.lens

/**
 * A point on the upright picture the viewer shows, in the sensor buffer that picture is
 * rotated from. [rotationDegrees] is clockwise and is the lens sensor's rotation plus the
 * orientation added to the frame; both are quarter-turns.
 */
object FocusMap {
    fun toSensor(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> {
        val px = x.coerceIn(0f, 1f)
        val py = y.coerceIn(0f, 1f)
        return when (Math.floorMod(rotationDegrees, 360)) {
            90 -> py to 1f - px
            180 -> 1f - px to 1f - py
            270 -> 1f - py to px
            else -> px to py
        }
    }
}
