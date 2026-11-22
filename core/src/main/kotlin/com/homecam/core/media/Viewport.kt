package com.homecam.core.media

import kotlin.math.roundToInt

/**
 * Digital zoom and pan over one upright video frame. [scale] runs from one to four
 * times. [centerX] and [centerY] are fractions of that frame, and the window around
 * them (1/[scale] across) stays inside it, which pins the centre at one times.
 *
 * A drag in [pannedBy] is a fraction of the view. The picture follows the finger, so
 * the centre moves the other way by the drag divided by [scale]. The focus of
 * [zoomedBy] is a fraction of the view too, and the frame point under it stays there.
 *
 * [bufferCrop] is that window in encoded-buffer pixels. [rotation] is the clockwise
 * quarter turn the renderer applies to show the buffer upright (WebRTC
 * `VideoFrame.getRotation`), so at 90 and 270 the upright width is the buffer height
 * and the crop is the window mapped back through that turn. Width and height are even
 * and at least two: the chroma planes are subsampled 2×2.
 */
data class Viewport(
    val scale: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {
    val zoomed: Boolean get() = scale > 1f

    fun zoomedBy(factor: Float, focusX: Float, focusY: Float): Viewport {
        val zoomed = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val shift = 1f / scale - 1f / zoomed
        return copy(
            scale = zoomed,
            centerX = centerX + (focusX - 0.5f) * shift,
            centerY = centerY + (focusY - 0.5f) * shift,
        ).clamped()
    }

    fun pannedBy(dx: Float, dy: Float): Viewport = copy(
        centerX = centerX - dx / scale,
        centerY = centerY - dy / scale,
    ).clamped()

    fun toFrame(viewX: Float, viewY: Float) = FramePoint(
        centerX + (viewX - 0.5f) / scale,
        centerY + (viewY - 0.5f) / scale,
    )

    fun visible(): FrameRect {
        val half = 0.5f / scale
        return FrameRect(centerX - half, centerY - half, centerX + half, centerY + half)
    }

    fun bufferCrop(bufferWidth: Int, bufferHeight: Int, rotation: Int): CropRect {
        val window = visible()
        val buffer = when (quarterTurns(rotation)) {
            90 -> FrameRect(window.top, 1f - window.right, window.bottom, 1f - window.left)
            180 -> FrameRect(1f - window.right, 1f - window.bottom, 1f - window.left, 1f - window.top)
            270 -> FrameRect(1f - window.bottom, window.left, 1f - window.top, window.right)
            else -> window
        }
        val (x, width) = evenSpan(buffer.left, buffer.right, bufferWidth)
        val (y, height) = evenSpan(buffer.top, buffer.bottom, bufferHeight)
        return CropRect(x, y, width, height)
    }

    private fun clamped(): Viewport {
        val half = 0.5f / scale
        return copy(
            centerX = centerX.coerceIn(half, 1f - half),
            centerY = centerY.coerceIn(half, 1f - half),
        )
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        val IDENTITY = Viewport()

        private fun quarterTurns(rotation: Int): Int = ((rotation % 360) + 360) % 360

        private fun evenSpan(startFraction: Float, endFraction: Float, size: Int): Pair<Int, Int> {
            if (size < 2) return 0 to size.coerceAtLeast(0)
            val start = startFraction.coerceIn(0f, 1f) * size
            val end = endFraction.coerceIn(0f, 1f) * size
            val span = (((end - start) / 2f).roundToInt() * 2).coerceIn(2, size - size % 2)
            val origin = ((start + end) / 2f - span / 2f).roundToInt().coerceIn(0, size - span)
            return origin to span
        }
    }
}

/** A point, in fractions of the upright frame. */
data class FramePoint(val x: Float, val y: Float)

/** A window, in fractions of the upright frame. */
data class FrameRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A crop in encoded-buffer pixels. [width] and [height] are even and at least 2. */
data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)
