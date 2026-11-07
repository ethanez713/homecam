package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.media.OsdRaster
import com.homecam.app.media.PrivacyMaskProcessor
import com.homecam.app.media.WebRtc
import com.homecam.proto.PrivacyMask
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/**
 * The timestamp overlay is burned into the I420 frame the privacy mask already reads
 * back. With it off and no mask, that readback does not happen.
 */
@RunWith(AndroidJUnit4::class)
class OsdTest {

    @Before
    fun loadNative() {
        WebRtc.factory(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun overlayChangesOnlyTheCornerThatBecomesBottomLeft() {
        val raster = OsdRaster { NOW }
        val on = PrivacyMaskProcessor(raster).apply {
            osdEnabled = true
            osdAlias = ALIAS
        }
        val upright = stamp(on, raster, rotation = 0, orientation = 0)
        assertTrue("overlay did not draw text", upright.pixels.any { (it.toInt() and 0xFF) <= 30 })
        assertTrue("overlay text is not light", upright.pixels.any { (it.toInt() and 0xFF) >= 180 })

        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val shown = stamp(on, raster, rotation, orientation = 0)
            assertCorner(rotation, shown.box)
            assertTextHeight(rotation, shown.box)
            assertOnlyInside(shown.off, shown.on, shown.box)
            val undone = clockwise(shown.pixels, shown.box.width, shown.box.height, rotation)
            assertTrue(
                "rotation $rotation does not come out upright",
                upright.pixels.contentEquals(undone),
            )
        }
    }

    @Test
    fun cameraOrientationIsPartOfTheTurnTheOverlayUndoes() {
        val raster = OsdRaster { NOW }
        val on = PrivacyMaskProcessor(raster).apply {
            osdEnabled = true
            osdAlias = ALIAS
        }
        val upright = stamp(on, raster, rotation = 0, orientation = 0)
        val shown = stamp(on, raster, rotation = 0, orientation = 90)
        assertCorner(90, shown.box)
        assertOnlyInside(shown.off, shown.on, shown.box)
        val undone = clockwise(shown.pixels, shown.box.width, shown.box.height, 90)
        assertTrue(upright.pixels.contentEquals(undone))
    }

    @Test
    fun noOverlayAndNoMaskForwardsTheSameBuffer() {
        val processor = PrivacyMaskProcessor()
        val buffer = JavaI420Buffer.allocate(16, 16)
        val frame = VideoFrame(buffer, 0, 0)
        var seen: VideoFrame.Buffer? = null
        processor.setSink { seen = it.buffer }
        processor.onFrameCaptured(frame)
        assertTrue(seen === buffer)
        frame.release()

        processor.orientationDegrees = 90
        val turned = JavaI420Buffer.allocate(16, 16)
        val turnedFrame = VideoFrame(turned, 0, 0)
        var rotation = -1
        processor.setSink {
            seen = it.buffer
            rotation = it.rotation
        }
        processor.onFrameCaptured(turnedFrame)
        assertTrue(seen === turned)
        assertEquals(90, rotation)
        turnedFrame.release()
    }

    @Test
    fun anUnreadableFrameIsDroppedOnlyWhenItWouldHaveToBeRead() {
        val buffer = UnreadableBuffer()
        val frame = { VideoFrame(buffer, 0, 0) }

        var forwarded = false
        PrivacyMaskProcessor().apply { setSink { forwarded = true } }.onFrameCaptured(frame())
        assertTrue(forwarded)

        forwarded = false
        PrivacyMaskProcessor().apply {
            osdEnabled = true
            setSink { forwarded = true }
        }.onFrameCaptured(frame())
        assertFalse(forwarded)

        forwarded = false
        PrivacyMaskProcessor().apply {
            mask = fullMask()
            setSink { forwarded = true }
        }.onFrameCaptured(frame())
        assertFalse(forwarded)
    }

    @Test
    fun maskAndOverlayChangeOnlyTheirOwnPixels() {
        val raster = OsdRaster { NOW }
        val processor = PrivacyMaskProcessor(raster).apply {
            osdEnabled = true
            osdAlias = ALIAS
            mask = PrivacyMask.newBuilder()
                .addRects(PrivacyMask.Rect.newBuilder().setLeft(0f).setTop(0f).setRight(0.4f).setBottom(0.4f))
                .build()
        }
        val pristine = filled(SIZE, SIZE)
        val on = process(processor, SIZE, SIZE, 0)
        val box = raster.bounds(SIZE, SIZE, 0, ALIAS)
        assertNotNull(box)
        val overlay = box!!
        val maskRight = (0.4f * SIZE).toInt()
        val maskBottom = (0.4f * SIZE).toInt()

        assertEquals(16, on.y[20 * on.strideY + 20].toInt() and 0xFF)
        assertEquals(40, on.y[200 * on.strideY + 200].toInt() and 0xFF)
        var overlayDiffs = 0
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                val index = row * on.strideY + col
                if (pristine.y[index] == on.y[index]) continue
                val inMask = col < maskRight && row < maskBottom
                val inOverlay = col >= overlay.left && col < overlay.right && row >= overlay.top && row < overlay.bottom
                assertTrue("luma ($col,$row) changed outside the mask and the overlay", inMask || inOverlay)
                if (inOverlay) overlayDiffs++
            }
        }
        assertTrue("overlay did not change the frame", overlayDiffs > 0)

        val chroma = (SIZE + 1) / 2
        val maskChromaRight = (0.4f * chroma).toInt()
        val maskChromaBottom = (0.4f * chroma).toInt()
        for (row in 0 until chroma) {
            for (col in 0 until chroma) {
                val uChanged = pristine.u[row * pristine.strideU + col] != on.u[row * on.strideU + col]
                val vChanged = pristine.v[row * pristine.strideV + col] != on.v[row * on.strideV + col]
                if (!uChanged && !vChanged) continue
                val inMask = col < maskChromaRight && row < maskChromaBottom
                val overlapsOverlay = col * 2 < overlay.right && (col + 1) * 2 > overlay.left &&
                    row * 2 < overlay.bottom && (row + 1) * 2 > overlay.top
                assertTrue("chroma ($col,$row) changed outside the mask and the overlay", inMask || overlapsOverlay)
            }
        }
    }

    private data class Stamp(
        val off: Planes,
        val on: Planes,
        val box: OsdRaster.Box,
        val pixels: ByteArray,
    )

    private fun stamp(processor: PrivacyMaskProcessor, raster: OsdRaster, rotation: Int, orientation: Int): Stamp {
        processor.orientationDegrees = orientation
        val shown = (rotation + orientation) % 360
        val off = process(PrivacyMaskProcessor(), SIZE, SIZE, rotation)
        val on = process(processor, SIZE, SIZE, rotation)
        val box = raster.bounds(SIZE, SIZE, shown, ALIAS)
        assertNotNull(box)
        val region = box!!
        return Stamp(off, on, region, lumaOf(on, region))
    }

    private fun assertCorner(rotation: Int, box: OsdRaster.Box) {
        when (rotation) {
            0 -> {
                assertEquals(0, box.left)
                assertEquals(SIZE, box.bottom)
                assertTrue(box.width > box.height)
            }
            90 -> {
                assertEquals(SIZE, box.right)
                assertEquals(SIZE, box.bottom)
                assertTrue(box.height > box.width)
            }
            180 -> {
                assertEquals(SIZE, box.right)
                assertEquals(0, box.top)
                assertTrue(box.width > box.height)
            }
            270 -> {
                assertEquals(0, box.left)
                assertEquals(0, box.top)
                assertTrue(box.height > box.width)
            }
        }
    }

    private fun assertTextHeight(rotation: Int, box: OsdRaster.Box) {
        // The frame is square, so the upright picture is SIZE tall at every quarter.
        val extent = if (rotation == 90 || rotation == 270) box.width else box.height
        assertTrue("text is shorter than 3.5% of the picture ($extent)", extent >= (SIZE * 0.035f).toInt())
        assertTrue("text is much taller than 3.5% of the picture ($extent)", extent <= SIZE * 0.15f)
    }

    private fun assertOnlyInside(before: Planes, after: Planes, box: OsdRaster.Box) {
        var diffs = 0
        for (row in 0 until before.height) {
            for (col in 0 until before.width) {
                val b = before.y[row * before.strideY + col]
                val a = after.y[row * after.strideY + col]
                if (b == a) continue
                diffs++
                assertTrue(
                    "luma ($col,$row) changed outside $box",
                    col >= box.left && col < box.right && row >= box.top && row < box.bottom,
                )
            }
        }
        assertTrue("overlay did not change the frame", diffs > 0)
        val chromaW = (before.width + 1) / 2
        val chromaH = (before.height + 1) / 2
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val uChanged = before.u[row * before.strideU + col] != after.u[row * after.strideU + col]
                val vChanged = before.v[row * before.strideV + col] != after.v[row * after.strideV + col]
                if (!uChanged && !vChanged) continue
                val overlaps = col * 2 < box.right && (col + 1) * 2 > box.left &&
                    row * 2 < box.bottom && (row + 1) * 2 > box.top
                assertTrue("chroma ($col,$row) changed outside $box", overlaps)
            }
        }
    }

    private fun lumaOf(planes: Planes, box: OsdRaster.Box): ByteArray {
        val out = ByteArray(box.width * box.height)
        for (row in 0 until box.height) {
            val src = (box.top + row) * planes.strideY + box.left
            for (col in 0 until box.width) out[row * box.width + col] = planes.y[src + col]
        }
        return out
    }

    /** Clockwise, matching the turn a consumer applies to the buffer. */
    private fun clockwise(src: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        var pixels = src
        var w = width
        var h = height
        repeat((((degrees % 360) + 360) % 360) / 90) {
            val dst = ByteArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dx = h - 1 - y
                    val dy = x
                    dst[dy * h + dx] = pixels[y * w + x]
                }
            }
            pixels = dst
            val swap = w
            w = h
            h = swap
        }
        return pixels
    }

    private fun process(processor: PrivacyMaskProcessor, width: Int, height: Int, rotation: Int): Planes {
        val buffer = JavaI420Buffer.allocate(width, height)
        fill(buffer)
        val frame = VideoFrame(buffer, rotation, 0)
        var out: Planes? = null
        processor.setSink { delivered ->
            val i420 = delivered.buffer.toI420() ?: return@setSink
            try {
                out = Planes(
                    i420.width,
                    i420.height,
                    bytes(i420.dataY),
                    i420.strideY,
                    bytes(i420.dataU),
                    i420.strideU,
                    bytes(i420.dataV),
                    i420.strideV,
                )
            } finally {
                i420.release()
            }
        }
        processor.onFrameCaptured(frame)
        frame.release()
        return out ?: error("frame dropped")
    }

    private fun filled(width: Int, height: Int): Planes {
        val buffer = JavaI420Buffer.allocate(width, height)
        fill(buffer)
        val planes = Planes(
            width,
            height,
            bytes(buffer.dataY),
            buffer.strideY,
            bytes(buffer.dataU),
            buffer.strideU,
            bytes(buffer.dataV),
            buffer.strideV,
        )
        buffer.release()
        return planes
    }

    private fun fill(buffer: JavaI420Buffer) {
        paint(buffer.dataY, buffer.strideY, buffer.width, buffer.height, 40)
        val chromaW = (buffer.width + 1) / 2
        val chromaH = (buffer.height + 1) / 2
        paint(buffer.dataU, buffer.strideU, chromaW, chromaH, 150)
        paint(buffer.dataV, buffer.strideV, chromaW, chromaH, 90)
    }

    private fun paint(plane: ByteBuffer, stride: Int, width: Int, height: Int, value: Int) {
        val byte = value.toByte()
        for (row in 0 until height) {
            val base = row * stride
            for (col in 0 until width) plane.put(base + col, byte)
        }
    }

    private fun bytes(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        copy.clear()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    private fun fullMask(): PrivacyMask = PrivacyMask.newBuilder()
        .addRects(PrivacyMask.Rect.newBuilder().setLeft(0f).setTop(0f).setRight(1f).setBottom(1f))
        .build()

    private class UnreadableBuffer : VideoFrame.Buffer {
        override fun getWidth(): Int = 8
        override fun getHeight(): Int = 8
        override fun toI420(): VideoFrame.I420Buffer? = null
        override fun retain() = Unit
        override fun release() = Unit
        override fun cropAndScale(
            cropX: Int,
            cropY: Int,
            cropWidth: Int,
            cropHeight: Int,
            scaleWidth: Int,
            scaleHeight: Int,
        ): VideoFrame.Buffer = this
    }

    private data class Planes(
        val width: Int,
        val height: Int,
        val y: ByteArray,
        val strideY: Int,
        val u: ByteArray,
        val strideU: Int,
        val v: ByteArray,
        val strideV: Int,
    )

    private companion object {
        const val SIZE = 360
        const val NOW = 1_700_000_000_000L
        const val ALIAS = "Front door"
    }
}
