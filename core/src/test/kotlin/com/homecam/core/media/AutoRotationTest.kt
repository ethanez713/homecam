package com.homecam.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoRotationTest {

    @Test
    fun `device quarters map to frame rotation for the back and front lenses`() {
        val back = listOf(0 to 0, 90 to 90, 180 to 180, 270 to 270)
        val front = listOf(0 to 0, 90 to 270, 180 to 180, 270 to 90)
        back.forEach { (device, extra) ->
            assertEquals(extra, AutoRotation.extraDegrees(device, front = false))
        }
        front.forEach { (device, extra) ->
            assertEquals(extra, AutoRotation.extraDegrees(device, front = true))
        }
    }

    @Test
    fun `a new quarter held under a second or outside the band is ignored`() {
        val held = AutoRotation.Hysteresis(0)
        assertEquals(0, held.update(80, 0))
        assertEquals(0, held.update(100, 999))

        val outside = AutoRotation.Hysteresis(180)
        assertEquals(180, outside.update(45, 0))
        assertEquals(180, outside.update(50, 5_000))
        assertEquals(180, outside.update(31, 9_000))
    }

    @Test
    fun `a quarter is taken once the angle has stayed within thirty degrees for a second`() {
        val hysteresis = AutoRotation.Hysteresis(0)
        assertEquals(0, hysteresis.update(80, 0))
        assertEquals(0, hysteresis.update(100, 999))
        assertEquals(90, hysteresis.update(95, 1_000))
    }

    @Test
    fun `leaving the band before a second restarts the hold`() {
        val hysteresis = AutoRotation.Hysteresis(0)
        assertEquals(0, hysteresis.update(90, 0))
        assertEquals(0, hysteresis.update(45, 900))
        assertEquals(0, hysteresis.update(90, 1_000))
        assertEquals(90, hysteresis.update(90, 2_000))
    }

    @Test
    fun `an unknown angle keeps the last quarter`() {
        val hysteresis = AutoRotation.Hysteresis(270)
        assertEquals(270, hysteresis.update(0, 0))
        assertEquals(270, hysteresis.update(AutoRotation.UNKNOWN, 500))
        assertEquals(270, hysteresis.update(AutoRotation.UNKNOWN, 5_000))
        assertEquals(270, hysteresis.update(0, 1_000))
        assertEquals(0, hysteresis.update(0, 2_000))
    }
}
