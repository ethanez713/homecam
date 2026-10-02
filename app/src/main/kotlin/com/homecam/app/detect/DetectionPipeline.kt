package com.homecam.app.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.homecam.core.crypto.AlertLimits
import com.homecam.core.detect.Detection
import com.homecam.core.detect.DetectionPolicy
import com.homecam.core.detect.MotionGate
import com.homecam.proto.SettingsSnapshot
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.YuvHelper
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The gated half of §6: cheap frame differencing decides which frames are worth the
 * detector, and the detector runs at a few frames a second on downscaled input.
 *
 * It is a [VideoSink] on the same masked capture the recorder uses, so detection can never
 * see a region the owner masked out.
 */
class DetectionPipeline(
    context: Context,
    private val settings: () -> SettingsSnapshot,
    /**
     * [thumbnailJpeg] is a small still of the frame that fired, bounded for the alert path
     * and null for a motion-only event that carries no labels. It comes off the masked
     * capture, so it can never show a region the owner masked out.
     */
    private val onEvent: (detections: List<LabelledDetection>, thumbnailJpeg: ByteArray?) -> Unit,
) : VideoSink {

    data class LabelledDetection(val category: String, val detection: Detection)

    private val detector = ObjectDetector.create(context)
    private val gate = MotionGate()
    private val busy = AtomicBoolean(false)
    private val inference = Any()
    @Volatile private var closed = false

    private var lastDetectAtMs = 0L
    private var lastGridAtMs = 0L

    val available: Boolean get() = detector != null

    @Volatile var framesPerSecond: Float = 0f
        private set

    /** Mean luma of the last graded frame (0..255), so a response can tell dark from day. */
    @Volatile var averageLuma: Float = 255f
        private set

    override fun onFrame(frame: VideoFrame) {
        val now = System.currentTimeMillis()
        if (now - lastGridAtMs < GRID_INTERVAL_MS) return
        lastGridAtMs = now

        val snapshot = settings()
        if (!snapshot.armed || !withinSchedule(snapshot, now)) {
            gate.reset()
            return
        }

        // Scaled on the GPU before the readback: the detector's input is a few hundred
        // pixels wide, and reading back 1080p five times a second is most of this app's CPU.
        val scale = ANALYSIS_LONG_EDGE.toFloat() / maxOf(frame.buffer.width, frame.buffer.height)
        val small = if (scale < 1f) {
            frame.buffer.cropAndScale(
                0, 0, frame.buffer.width, frame.buffer.height,
                (frame.buffer.width * scale).toInt() / 2 * 2, (frame.buffer.height * scale).toInt() / 2 * 2,
            )
        } else {
            frame.buffer.also { it.retain() }
        }
        val i420 = small.toI420().also { small.release() } ?: return
        try {
            val grid = lumaGrid(i420)
            averageLuma = grid.average().toFloat()
            if (!gate.accept(grid, now)) return
            if (now - lastDetectAtMs < DETECT_INTERVAL_MS) return
            if (!busy.compareAndSet(false, true)) return
            val previousDetectAtMs = lastDetectAtMs
            lastDetectAtMs = now

            val bitmap = upright(toBitmap(i420), frame.rotation)
            val detections = synchronized(inference) {
                if (closed) null else detector?.detect(bitmap).orEmpty()
            } ?: run { bitmap.recycle(); return }

            val accepted = detections.mapNotNull { detection ->
                DetectionPolicy.accepts(snapshot.detectionMode, snapshot.detectionZone, detection)
                    ?.let { LabelledDetection(it, detection) }
            }
            if (previousDetectAtMs > 0) framesPerSecond = 1000f / (now - previousDetectAtMs).coerceAtLeast(1)
            if (accepted.isNotEmpty()) {
                onEvent(accepted, boundedJpeg(bitmap, AlertLimits.MAX_THUMBNAIL_BYTES))
            } else if (snapshot.detectionMode.motionOnly) {
                onEvent(accepted, null)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "detection frame dropped", e)
        } finally {
            busy.set(false)
            i420.release()
        }
    }

    /**
     * Inference runs on the capture thread and close() on the main thread; closing the
     * interpreter under a running inference is a native use-after-free, so close waits.
     */
    fun close() = synchronized(inference) {
        closed = true
        detector?.close()
    }

    private fun withinSchedule(snapshot: SettingsSnapshot, nowMs: Long): Boolean {
        val local = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
        return DetectionPolicy.isWithinSchedule(
            snapshot.detectionSchedule,
            minuteOfDay = local.hour * 60 + local.minute,
            dayOfWeek = local.dayOfWeek.value - 1,
        )
    }

    /**
     * A coarse luma grid, sampled straight from the Y plane. Differencing at capture
     * resolution would cost more than the detector call it is meant to avoid.
     */
    private fun lumaGrid(buffer: VideoFrame.I420Buffer): IntArray {
        val grid = IntArray(GRID * GRID)
        val cellWidth = buffer.width / GRID
        val cellHeight = buffer.height / GRID
        if (cellWidth == 0 || cellHeight == 0) return grid

        val y = buffer.dataY
        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                var total = 0
                var samples = 0
                var pixelRow = row * cellHeight
                while (pixelRow < (row + 1) * cellHeight) {
                    var pixelColumn = column * cellWidth
                    while (pixelColumn < (column + 1) * cellWidth) {
                        val index = pixelRow * buffer.strideY + pixelColumn
                        if (index < y.capacity()) {
                            total += y.get(index).toInt() and 0xFF
                            samples++
                        }
                        pixelColumn += CELL_SAMPLE_STEP
                    }
                    pixelRow += CELL_SAMPLE_STEP
                }
                grid[row * GRID + column] = if (samples == 0) 0 else total / samples
            }
        }
        return grid
    }

    /** A camera on its side shows the detector sideways people, which it does not recognise. */
    private fun upright(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation % 360 == 0) return bitmap
        val turn = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, turn, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun toBitmap(buffer: VideoFrame.I420Buffer): Bitmap {
        val width = buffer.width
        val height = buffer.height
        val nv21 = ByteBuffer.allocateDirect(width * height * 3 / 2)
        YuvHelper.I420ToNV12(
            buffer.dataY, buffer.strideY,
            buffer.dataV, buffer.strideV,
            buffer.dataU, buffer.strideU,
            nv21, width, height,
        )
        val yuv = android.graphics.YuvImage(
            ByteArray(nv21.capacity()).also { nv21.rewind(); nv21.get(it) },
            android.graphics.ImageFormat.NV21, width, height, null,
        )
        val jpeg = java.io.ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), JPEG_QUALITY, jpeg)
        val bytes = jpeg.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** A small JPEG of the detected frame, squeezed under [maxBytes] for the alert path. */
    private fun boundedJpeg(bitmap: Bitmap, maxBytes: Int): ByteArray? {
        val scaled = scaleToWidth(bitmap, ALERT_THUMBNAIL_WIDTH)
        var quality = ALERT_THUMBNAIL_QUALITY
        var bytes: ByteArray
        do {
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= ALERT_THUMBNAIL_QUALITY_STEP
        } while (bytes.size > maxBytes && quality >= ALERT_THUMBNAIL_MIN_QUALITY)
        if (scaled !== bitmap) scaled.recycle()
        return bytes.takeIf { it.size <= maxBytes }
    }

    private fun scaleToWidth(bitmap: Bitmap, width: Int): Bitmap {
        if (bitmap.width <= width) return bitmap
        val height = (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private companion object {
        const val TAG = "DetectionPipeline"
        const val ALERT_THUMBNAIL_WIDTH = 160
        const val ALERT_THUMBNAIL_QUALITY = 60
        const val ALERT_THUMBNAIL_QUALITY_STEP = 10
        const val ALERT_THUMBNAIL_MIN_QUALITY = 20
        const val GRID = 16
        const val CELL_SAMPLE_STEP = 4
        const val GRID_INTERVAL_MS = 200L
        const val ANALYSIS_LONG_EDGE = 640
        const val DETECT_INTERVAL_MS = 400L
        const val JPEG_QUALITY = 80
    }
}
