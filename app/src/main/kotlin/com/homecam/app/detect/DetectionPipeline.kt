package com.homecam.app.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
    private val onEvent: (List<LabelledDetection>) -> Unit,
) : VideoSink {

    data class LabelledDetection(val category: String, val detection: Detection)

    private val detector = ObjectDetector.create(context)
    private val gate = MotionGate()
    private val busy = AtomicBoolean(false)

    private var lastDetectAtMs = 0L
    private var lastGridAtMs = 0L

    val available: Boolean get() = detector != null

    @Volatile var framesPerSecond: Float = 0f
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

        val i420 = frame.buffer.toI420() ?: return
        try {
            if (!gate.accept(lumaGrid(i420), now)) return
            if (now - lastDetectAtMs < DETECT_INTERVAL_MS) return
            if (!busy.compareAndSet(false, true)) return
            lastDetectAtMs = now

            val bitmap = toBitmap(i420)
            val detections = detector?.detect(bitmap).orEmpty()
            bitmap.recycle()

            val accepted = detections.mapNotNull { detection ->
                DetectionPolicy.accepts(snapshot.detectionMode, snapshot.detectionZone, detection)
                    ?.let { LabelledDetection(it, detection) }
            }
            framesPerSecond = 1000f / (now - lastDetectAtMs).coerceAtLeast(1)
            if (accepted.isNotEmpty() || snapshot.detectionMode.motionOnly) onEvent(accepted)
        } catch (e: Exception) {
            Log.w(TAG, "detection frame dropped", e)
        } finally {
            busy.set(false)
            i420.release()
        }
    }

    fun close() = detector?.close()

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

    private companion object {
        const val TAG = "DetectionPipeline"
        const val GRID = 16
        const val CELL_SAMPLE_STEP = 4
        const val GRID_INTERVAL_MS = 200L
        const val DETECT_INTERVAL_MS = 400L
        const val JPEG_QUALITY = 80
    }
}
