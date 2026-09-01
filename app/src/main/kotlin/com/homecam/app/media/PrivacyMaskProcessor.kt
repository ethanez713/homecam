package com.homecam.app.media

import com.homecam.proto.PrivacyMask
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * A quarter-turn added to every delivered frame's rotation. Applied here, upstream of
     * both the live track and the recorder, so a viewer's rotation lands identically on
     * the stream and on what gets written — the same reason the mask itself lives here.
     */
    @Volatile var orientationDegrees: Int = 0

    private var sink: VideoSink? = null

    /**
     * Extra consumers — the recorder — attached downstream of the mask, so they see
     * exactly the frames a viewer would and cannot be given the unmasked original.
     */
    private val extraSinks = CopyOnWriteArrayList<VideoSink>()

    override fun setSink(sink: VideoSink?) { this.sink = sink }

    fun addSink(sink: VideoSink) { extraSinks.addIfAbsent(sink) }

    fun removeSink(sink: VideoSink) { extraSinks.remove(sink) }

    override fun onCapturerStarted(success: Boolean) = Unit
    override fun onCapturerStopped() = Unit

    override fun onFrameCaptured(frame: VideoFrame) {
        val rects = mask.rectsList
        if (rects.isEmpty()) {
            // The untouched fast path keeps a texture frame on the GPU all the way to the
            // encoder, so only pay for a rewrapped frame when a rotation is actually set.
            if (orientationDegrees == 0) {
                deliver(frame)
            } else {
                deliver(VideoFrame(frame.buffer, rotationOf(frame), frame.timestampNs))
            }
            return
        }
        // A frame we cannot mask is a frame we must not forward: silently streaming the
        // unmasked version would defeat the one control that promises the pixels never
        // leave the phone.
        val masked = applyMask(frame, rects, rotationOf(frame)) ?: return
        deliver(masked)
        masked.release()
    }

    private fun rotationOf(frame: VideoFrame): Int = (frame.rotation + orientationDegrees) % 360

    private fun deliver(frame: VideoFrame) {
        sink?.onFrame(frame)
        extraSinks.forEach { it.onFrame(frame) }
    }

    private fun applyMask(frame: VideoFrame, rects: List<PrivacyMask.Rect>, rotation: Int): VideoFrame? {
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
        return VideoFrame(copy, rotation, frame.timestampNs)
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
