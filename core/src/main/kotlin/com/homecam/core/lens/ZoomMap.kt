package com.homecam.core.lens

/**
 * The zoom factor at a position on the viewer's 0..1 slider. Linear in the reciprocal of
 * the factor, which is how CameraX spaces its own linear zoom, so equal slider steps crop
 * equal slices of the view instead of bunching at the wide end.
 */
object ZoomMap {

    fun factorAt(position: Float, min: Float, max: Float): Float {
        if (min <= 0f || max <= min) return min.coerceAtLeast(1f)
        val t = position.coerceIn(0f, 1f)
        return 1f / (1f / min - t * (1f / min - 1f / max))
    }

    fun label(factor: Float): String = "%.1f×".format(factor)
}
