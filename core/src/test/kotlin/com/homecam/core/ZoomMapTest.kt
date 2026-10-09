package com.homecam.core

import com.homecam.core.lens.ZoomMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMapTest {

    @Test
    fun `the ends of the slider are the ends of the lens's range`() {
        assertEquals(1f, ZoomMap.factorAt(0f, 1f, 8f), 0.0001f)
        assertEquals(8f, ZoomMap.factorAt(1f, 1f, 8f), 0.0001f)
        assertEquals(0.5f, ZoomMap.factorAt(0f, 0.5f, 1f), 0.0001f)
        assertEquals(1f, ZoomMap.factorAt(1f, 0.5f, 1f), 0.0001f)
    }

    @Test
    fun `equal slider steps crop equal slices of the view`() {
        val half = ZoomMap.factorAt(0.5f, 1f, 8f)

        assertEquals(1f / half, (1f + 1f / 8f) / 2f, 0.0001f)
        assertTrue("the middle of the slider is not the middle of the range", half < 4.5f)
    }

    @Test
    fun `a position off the slider stays on the lens`() {
        assertEquals(8f, ZoomMap.factorAt(3f, 1f, 8f), 0.0001f)
        assertEquals(1f, ZoomMap.factorAt(-1f, 1f, 8f), 0.0001f)
    }

    @Test
    fun `a lens that cannot zoom reads as one times`() {
        assertEquals(1f, ZoomMap.factorAt(0.7f, 1f, 1f), 0.0001f)
        assertEquals("1.0×", ZoomMap.label(1f))
        assertEquals("2.5×", ZoomMap.label(2.46f))
    }
}
