package com.homecam.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {

    @Test
    fun `the frame point under the pinch focus stays under it`() {
        val foci = listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f, 0.25f to 0.8f, 0.5f to 0.5f, 0.7f to 0.3f)
        listOf(1.5f, 2f, 3f, 4f).forEach { factor ->
            foci.forEach { (focusX, focusY) ->
                val start = Viewport.IDENTITY
                assertFocusStays(start, start.zoomedBy(factor, focusX, focusY), focusX, focusY)
            }
        }

        val twice = Viewport(2f, 0.5f, 0.5f)
        val four = Viewport(4f, 0.5f, 0.5f)
        listOf(0f to 1f, 0.2f to 0.8f, 0.5f to 0.5f, 0.9f to 0.1f).forEach { (focusX, focusY) ->
            assertFocusStays(twice, twice.zoomedBy(2f, focusX, focusY), focusX, focusY)
            assertFocusStays(four, four.zoomedBy(0.5f, focusX, focusY), focusX, focusY)
        }
        val offset = Viewport(4f, 0.3f, 0.7f)
        assertFocusStays(offset, offset.zoomedBy(0.5f, 0.2f, 0.9f), 0.2f, 0.9f)
    }

    @Test
    fun `a pan follows the finger`() {
        val start = Viewport(2f, 0.5f, 0.5f)
        val moved = start.pannedBy(0.2f, -0.1f)

        assertEquals(0.4f, moved.centerX, EPS)
        assertEquals(0.55f, moved.centerY, EPS)
        assertEquals(start.toFrame(0.5f, 0.5f).x - 0.1f, moved.toFrame(0.5f, 0.5f).x, EPS)
        assertEquals(start.toFrame(0.5f, 0.5f).y + 0.05f, moved.toFrame(0.5f, 0.5f).y, EPS)
    }

    @Test
    fun `a pan stops at each edge`() {
        listOf(1f, 2f, 4f).forEach { scale ->
            val half = 0.5f / scale
            val start = Viewport(scale, 0.5f, 0.5f)

            val left = start.pannedBy(FAR, 0f)
            assertEquals(half, left.centerX, EPS)
            assertEquals(0.5f, left.centerY, EPS)
            assertEquals(0f, left.visible().left, EPS)

            val right = start.pannedBy(-FAR, 0f)
            assertEquals(1f - half, right.centerX, EPS)
            assertEquals(1f, right.visible().right, EPS)

            val top = start.pannedBy(0f, FAR)
            assertEquals(half, top.centerY, EPS)
            assertEquals(0f, top.visible().top, EPS)

            val bottom = start.pannedBy(0f, -FAR)
            assertEquals(1f - half, bottom.centerY, EPS)
            assertEquals(1f, bottom.visible().bottom, EPS)

            val corner = start.pannedBy(-FAR, -FAR)
            assertEquals(1f - half, corner.centerX, EPS)
            assertEquals(1f - half, corner.centerY, EPS)
            assertInside(corner)
        }
    }

    @Test
    fun `scale stays between one and four`() {
        assertEquals(1f, Viewport.MIN_SCALE, EPS)
        assertEquals(4f, Viewport.MAX_SCALE, EPS)
        assertFalse(Viewport.IDENTITY.zoomed)

        val opened = Viewport(4f, 0.2f, 0.8f).zoomedBy(0.01f, 0.3f, 0.7f)
        assertEquals(1f, opened.scale, EPS)
        assertEquals(0.5f, opened.centerX, EPS)
        assertEquals(0.5f, opened.centerY, EPS)
        assertFalse(opened.zoomed)

        val capped = Viewport.IDENTITY.zoomedBy(100f, 0.2f, 0.8f)
        assertEquals(4f, capped.scale, EPS)
        assertEquals(0.275f, capped.centerX, EPS)
        assertEquals(0.725f, capped.centerY, EPS)
        assertTrue(capped.zoomed)
        assertInside(capped)

        val already = Viewport(4f, 0.3f, 0.6f)
        assertEquals(already, already.zoomedBy(2f, 0.2f, 0.4f))
        assertEquals(4f, Viewport(2f, 0.5f, 0.5f).zoomedBy(3f, 0.5f, 0.5f).scale, EPS)

        val pulled = Viewport(4f, 0.125f, 0.5f).zoomedBy(0.5f, 0.5f, 0.5f)
        assertEquals(2f, pulled.scale, EPS)
        assertEquals(0.25f, pulled.centerX, EPS)
        assertEquals(0f, pulled.visible().left, EPS)
    }

    @Test
    fun `a view fraction maps onto the frame fraction under it`() {
        val full = Viewport.IDENTITY
        assertEquals(0.2f, full.toFrame(0.2f, 0.7f).x, EPS)
        assertEquals(0.7f, full.toFrame(0.2f, 0.7f).y, EPS)
        assertEquals(FrameRect(0f, 0f, 1f, 1f), full.visible())

        val centered = Viewport(2f, 0.5f, 0.5f)
        assertEquals(FramePoint(0.25f, 0.25f), centered.toFrame(0f, 0f))
        assertEquals(FramePoint(0.5f, 0.5f), centered.toFrame(0.5f, 0.5f))
        assertEquals(FramePoint(0.75f, 0.75f), centered.toFrame(1f, 1f))
        assertEquals(FrameRect(0.25f, 0.25f, 0.75f, 0.75f), centered.visible())

        val shifted = Viewport(2f, 0.25f, 0.75f)
        assertEquals(FramePoint(0f, 0.5f), shifted.toFrame(0f, 0f))
        assertEquals(FramePoint(0.25f, 0.75f), shifted.toFrame(0.5f, 0.5f))
        assertEquals(FramePoint(0.5f, 1f), shifted.toFrame(1f, 1f))
        assertEquals(shifted.visible().left, shifted.toFrame(0f, 0f).x, EPS)
        assertEquals(shifted.visible().top, shifted.toFrame(0f, 0f).y, EPS)
        assertEquals(shifted.visible().right, shifted.toFrame(1f, 1f).x, EPS)
        assertEquals(shifted.visible().bottom, shifted.toFrame(1f, 1f).y, EPS)
    }

    @Test
    fun `the crop is that window turned back into buffer pixels`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            assertEquals(CropRect(0, 0, 1920, 1080), Viewport.IDENTITY.bufferCrop(1920, 1080, rotation))
            assertEquals(CropRect(0, 0, 1080, 1920), Viewport.IDENTITY.bufferCrop(1080, 1920, rotation))
        }

        // Window sits on the left and bottom of the upright frame: left 0, top 0.5, right 0.5, bottom 1.
        val shifted = Viewport(2f, 0.25f, 0.75f)
        val expected = mapOf(
            0 to CropRect(0, 240, 320, 240),
            90 to CropRect(320, 240, 320, 240),
            180 to CropRect(320, 0, 320, 240),
            270 to CropRect(0, 0, 320, 240),
        )
        expected.forEach { (rotation, crop) ->
            assertEquals(crop, shifted.bufferCrop(640, 480, rotation))
        }

        val windows = listOf(
            Viewport.IDENTITY,
            shifted,
            Viewport(4f, 0.125f, 0.875f),
            Viewport(3f, 0.4f, 0.62f),
            Viewport(2f, 0.5f, 0.5f),
        )
        val buffers = listOf(640 to 480, 1920 to 1080, 1080 to 1920)
        windows.forEach { viewport ->
            buffers.forEach { (width, height) ->
                listOf(0, 90, 180, 270).forEach { rotation ->
                    assertCropMatchesVisible(viewport, width, height, rotation)
                }
            }
        }

        val tiny = Viewport(4f, 0.5f, 0.5f).bufferCrop(6, 4, 0)
        assertEquals(2, tiny.width)
        assertEquals(2, tiny.height)
        assertTrue(tiny.x >= 0 && tiny.y >= 0)
        assertTrue(tiny.x + tiny.width <= 6)
        assertTrue(tiny.y + tiny.height <= 4)
    }

    private fun assertFocusStays(before: Viewport, after: Viewport, focusX: Float, focusY: Float) {
        val held = before.toFrame(focusX, focusY)
        val still = after.toFrame(focusX, focusY)
        assertEquals(held.x, still.x, EPS)
        assertEquals(held.y, still.y, EPS)
        assertInside(after)
    }

    private fun assertInside(viewport: Viewport) {
        val window = viewport.visible()
        assertTrue(window.left >= -EPS)
        assertTrue(window.top >= -EPS)
        assertTrue(window.right <= 1f + EPS)
        assertTrue(window.bottom <= 1f + EPS)
    }

    private fun assertCropMatchesVisible(viewport: Viewport, bufferWidth: Int, bufferHeight: Int, rotation: Int) {
        val crop = viewport.bufferCrop(bufferWidth, bufferHeight, rotation)
        assertEquals(0, crop.width % 2)
        assertEquals(0, crop.height % 2)
        assertTrue(crop.width >= 2)
        assertTrue(crop.height >= 2)
        assertTrue(crop.x >= 0 && crop.y >= 0)
        assertTrue(crop.x + crop.width <= bufferWidth)
        assertTrue(crop.y + crop.height <= bufferHeight)

        val upright = listOf(
            crop.x to crop.y,
            crop.x + crop.width to crop.y,
            crop.x to crop.y + crop.height,
            crop.x + crop.width to crop.y + crop.height,
        ).map { (x, y) ->
            turnedUpright(x.toFloat() / bufferWidth, y.toFloat() / bufferHeight, rotation)
        }
        val window = viewport.visible()
        val slopX = 2f / if (rotation == 90 || rotation == 270) bufferHeight else bufferWidth
        val slopY = 2f / if (rotation == 90 || rotation == 270) bufferWidth else bufferHeight
        assertEquals(window.left, upright.minOf { it.x }, slopX)
        assertEquals(window.right, upright.maxOf { it.x }, slopX)
        assertEquals(window.top, upright.minOf { it.y }, slopY)
        assertEquals(window.bottom, upright.maxOf { it.y }, slopY)
    }

    /** The same clockwise turn the renderer applies to the buffer. */
    private fun turnedUpright(bufferX: Float, bufferY: Float, rotation: Int): FramePoint = when (rotation) {
        90 -> FramePoint(1f - bufferY, bufferX)
        180 -> FramePoint(1f - bufferX, 1f - bufferY)
        270 -> FramePoint(bufferY, 1f - bufferX)
        else -> FramePoint(bufferX, bufferY)
    }

    private companion object {
        const val EPS = 0.0001f
        const val FAR = 10f
    }
}
