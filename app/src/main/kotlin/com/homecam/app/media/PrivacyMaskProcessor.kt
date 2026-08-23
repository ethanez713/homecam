package com.homecam.app.media

import com.homecam.proto.PrivacyMask
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.nio.ByteBuffer

/**
 * Blanks masked regions **in the frame, before it reaches the encoder** (§2.8), so masked
 * areas exist in neither the live stream nor the recording. This is what distinguishes a
 * privacy mask from a detection zone: a zone only limits where detection runs, and the
 * pixels still leave the phone.
 *
 * With no mask configured the frame is forwarded untouched. That matters for more than
 * tidiness: an untouched texture frame stays on the GPU all the way into the hardware
 * encoder, while masking forces a readback to I420. The cost is real, so it is paid only
 * by someone who actually asked for a mask.
 */
class PrivacyMaskProcessor : VideoProcessor {

    @Volatile var mask: PrivacyMask = PrivacyMask.getDefaultInstance()

    private var sink: VideoSink? = null

    override fun setSink(sink: VideoSink?) { this.sink = sink }

    override fun onCapturerStarted(success: Boolean) = Unit
    override fun onCapturerStopped() = Unit

    override fun onFrameCaptured(frame: VideoFrame) {
        val target = sink ?: return
        val rects = mask.rectsList
        if (rects.isEmpty()) {
            target.onFrame(frame)
            return
        }
        val masked = applyMask(frame, rects)
        if (masked == null) {
            // A frame we cannot mask is a frame we must not forward: silently streaming
            // the unmasked version would defeat the only control that promises the pixels
            // never leave.
            return
        }
        target.onFrame(masked)
        masked.release()
    }

    private fun applyMask(frame: VideoFrame, rects: List<PrivacyMask.Rect>): VideoFrame? {
        val i420 = frame.buffer.toI420() ?: return null
        val width = i420.width
        val height = i420.height

        val y = i420.dataY.duplicate()
        val u = i420.dataU.duplicate()
        val v = i420.dataV.duplicate()

        for (rect in rects) {
            blank(y, i420.strideY, width, height, rect, luma = true)
            blank(u, i420.strideU, (width + 1) / 2, (height + 1) / 2, rect, luma = false)
            blank(v, i420.strideV, (width + 1) / 2, (height + 1) / 2, rect, luma = false)
        }

        val copy = JavaI420Buffer.wrap(
            width, height,
            y, i420.strideY, u, i420.strideU, v, i420.strideV,
            null,
        )
        i420.release()
        return VideoFrame(copy, frame.rotation, frame.timestampNs)
    }

    /**
     * Black in I420 is Y=16 and U=V=128, not zero everywhere: zeroing the chroma planes
     * gives a green rectangle rather than a black one.
     */
    private fun blank(
        plane: ByteBuffer,
        stride: Int,
        planeWidth: Int,
        planeHeight: Int,
        rect: PrivacyMask.Rect,
        luma: Boolean,
    ) {
        val left = (rect.left.coerceIn(0f, 1f) * planeWidth).toInt()
        val right = (rect.right.coerceIn(0f, 1f) * planeWidth).toInt()
        val top = (rect.top.coerceIn(0f, 1f) * planeHeight).toInt()
        val bottom = (rect.bottom.coerceIn(0f, 1f) * planeHeight).toInt()
        if (right <= left || bottom <= top) return

        val value = if (luma) BLACK_LUMA else NEUTRAL_CHROMA
        val row = ByteArray(right - left) { value }
        for (line in top until bottom) {
            val offset = line * stride + left
            if (offset + row.size > plane.capacity()) continue
            plane.position(offset)
            plane.put(row)
        }
        plane.rewind()
    }

    private companion object {
        const val BLACK_LUMA: Byte = 16
        const val NEUTRAL_CHROMA: Byte = -128  // 128 unsigned
    }
}
