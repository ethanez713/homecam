package com.homecam.app.media

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import org.webrtc.VideoFrame
import org.webrtc.YuvHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Encodes a WebRTC I420 frame to JPEG bytes, for the snapshot and clip-thumbnail paths. */
object YuvJpeg {
    fun encode(buffer: VideoFrame.I420Buffer, quality: Int = 85): ByteArray {
        val width = buffer.width
        val height = buffer.height
        val nv21 = ByteBuffer.allocateDirect(width * height * 3 / 2)
        // U and V are passed in V-then-U order, which turns I420 into NV21 rather than NV12
        // — the ordering YuvImage expects.
        YuvHelper.I420ToNV12(
            buffer.dataY, buffer.strideY,
            buffer.dataV, buffer.strideV,
            buffer.dataU, buffer.strideU,
            nv21, width, height,
        )
        val yuv = YuvImage(
            ByteArray(nv21.capacity()).also { nv21.rewind(); nv21.get(it) },
            ImageFormat.NV21, width, height, null,
        )
        return ByteArrayOutputStream().use { out ->
            yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)
            out.toByteArray()
        }
    }
}
