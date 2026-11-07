package com.homecam.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/**
 * Timestamp and camera name burned into an I420 frame. The glyph mask is rebuilt only
 * when the second, the alias, or the frame geometry changes — never per frame.
 *
 * Consumers rotate the buffer clockwise by the frame's rotation to show it upright, so
 * the stamp is turned the other way and parked in the corner that lands bottom-left.
 */
class OsdRaster(
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    private var cached: Placed? = null

    fun bounds(width: Int, height: Int, rotation: Int, alias: String): Box? =
        placed(width, height, rotation, alias)?.box

    fun draw(
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        strideY: Int,
        strideU: Int,
        strideV: Int,
        width: Int,
        height: Int,
        rotation: Int,
        alias: String,
    ) {
        val stamp = placed(width, height, rotation, alias) ?: return
        writeLuma(y, strideY, width, height, stamp)
        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        writeChroma(u, strideU, chromaW, chromaH, stamp.box)
        writeChroma(v, strideV, chromaW, chromaH, stamp.box)
    }

    @Synchronized
    private fun placed(width: Int, height: Int, rotation: Int, alias: String): Placed? {
        val quarter = quarter(rotation)
        val key = Key(now() / 1000, alias, width, height, quarter)
        val hit = cached
        if (hit != null && hit.key == key) return hit
        val built = build(key) ?: return null
        cached = built
        return built
    }

    private fun build(key: Key): Placed? {
        val swapped = key.rotation == 90 || key.rotation == 270
        val uprightWidth = if (swapped) key.height else key.width
        val uprightHeight = if (swapped) key.width else key.height
        if (uprightWidth < 1 || uprightHeight < 1) return null

        val text = line(key.alias, key.second * 1000)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            color = Color.WHITE
            textSize = (uprightHeight * TEXT_FRACTION).coerceAtLeast(1f)
        }
        val natural = paint.measureText(text) + paint.textSize * PAD_FRACTION * 2
        if (natural > uprightWidth) {
            paint.textSize = (paint.textSize * uprightWidth / natural).coerceAtLeast(1f)
        }
        val pad = (paint.textSize * PAD_FRACTION).toInt().coerceAtLeast(1)
        val metrics = paint.fontMetricsInt
        val textWidth = ceil(paint.measureText(text).toDouble()).toInt().coerceAtLeast(1)
        val textHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1)
        val boxWidth = (textWidth + pad * 2).coerceIn(1, uprightWidth)
        val boxHeight = (textHeight + pad * 2).coerceIn(1, uprightHeight)

        val bitmap = Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        canvas.drawText(text, pad.toFloat(), (pad - metrics.ascent).toFloat(), paint)
        val pixels = IntArray(boxWidth * boxHeight)
        bitmap.getPixels(pixels, 0, boxWidth, 0, 0, boxWidth, boxHeight)
        bitmap.recycle()

        val (luma, placedWidth, placedHeight) = rotate(pixels, boxWidth, boxHeight, key.rotation)
        val (left, top) = when (key.rotation) {
            90 -> key.width - placedWidth to key.height - placedHeight
            180 -> key.width - placedWidth to 0
            270 -> 0 to 0
            else -> 0 to key.height - placedHeight
        }
        return Placed(key, luma, Box(left, top, placedWidth, placedHeight))
    }

    private fun line(alias: String, nowMs: Long): String {
        val time = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).format(CLOCK)
        val name = alias.trim()
        return if (name.isEmpty()) time else "$time $name"
    }

    /** Inverse of the clockwise turn a consumer applies, so the glyphs come out upright. */
    private fun rotate(pixels: IntArray, width: Int, height: Int, quarter: Int): Triple<ByteArray, Int, Int> {
        val swapped = quarter == 90 || quarter == 270
        val dstWidth = if (swapped) height else width
        val dstHeight = if (swapped) width else height
        val luma = ByteArray(dstWidth * dstHeight)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (dx, dy) = when (quarter) {
                    90 -> y to (width - 1 - x)
                    180 -> (width - 1 - x) to (height - 1 - y)
                    270 -> (height - 1 - y) to x
                    else -> x to y
                }
                luma[dy * dstWidth + dx] = limited(pixels[y * width + x])
            }
        }
        return Triple(luma, dstWidth, dstHeight)
    }

    /** Limited-range luma. The mask's black is 16, not 0, and the text should match that range. */
    private fun limited(argb: Int): Byte {
        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF
        val full = (red * 77 + green * 150 + blue * 29) ushr 8
        return (16 + full * 219 / 255).toByte()
    }

    private fun writeLuma(plane: ByteBuffer, stride: Int, frameWidth: Int, frameHeight: Int, stamp: Placed) {
        val box = stamp.box
        for (row in 0 until box.height) {
            val y = box.top + row
            if (y < 0 || y >= frameHeight) continue
            val dest = y * stride + box.left
            val src = row * box.width
            for (col in 0 until box.width) {
                val x = box.left + col
                if (x < 0 || x >= frameWidth) continue
                val index = dest + col
                if (index in 0 until plane.capacity()) plane.put(index, stamp.luma[src + col])
            }
        }
    }

    private fun writeChroma(plane: ByteBuffer, stride: Int, planeWidth: Int, planeHeight: Int, box: Box) {
        val left = (box.left / 2).coerceIn(0, planeWidth)
        val top = (box.top / 2).coerceIn(0, planeHeight)
        val right = ((box.right + 1) / 2).coerceIn(0, planeWidth)
        val bottom = ((box.bottom + 1) / 2).coerceIn(0, planeHeight)
        for (row in top until bottom) {
            val dest = row * stride + left
            for (col in 0 until right - left) {
                val index = dest + col
                if (index in 0 until plane.capacity()) plane.put(index, NEUTRAL_CHROMA)
            }
        }
    }

    private fun quarter(rotation: Int): Int {
        val wrapped = ((rotation % 360) + 360) % 360
        return if (wrapped % 90 == 0) wrapped else 0
    }

    private data class Key(val second: Long, val alias: String, val width: Int, val height: Int, val rotation: Int)

    private class Placed(val key: Key, val luma: ByteArray, val box: Box)

    private companion object {
        const val TEXT_FRACTION = 0.035f
        const val PAD_FRACTION = 0.35f
        // 128 unsigned. Limited-range black and white are both neutral here, matching the mask.
        const val NEUTRAL_CHROMA: Byte = -128
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
