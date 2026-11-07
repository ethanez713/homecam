package com.homecam.core.lens

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusMapTest {

    @Test
    fun `zero rotation keeps the point`() {
        assertEquals(0.25f to 0.75f, FocusMap.toSensor(0.25f, 0.75f, 0))
    }

    @Test
    fun `a quarter turn maps the upright picture back onto the sensor buffer`() {
        assertEquals(0f to 0.5f, FocusMap.toSensor(0.5f, 0f, 90))
        assertEquals(0.75f to 0.25f, FocusMap.toSensor(0.25f, 0.75f, 180))
        assertEquals(1f to 0.5f, FocusMap.toSensor(0.5f, 0f, 270))
    }

    @Test
    fun `rotation is a quarter turn modulo 360`() {
        assertEquals(FocusMap.toSensor(0.25f, 0.75f, 90), FocusMap.toSensor(0.25f, 0.75f, 450))
        assertEquals(FocusMap.toSensor(0.25f, 0.75f, 270), FocusMap.toSensor(0.25f, 0.75f, -90))
    }

    @Test
    fun `points outside the picture are pulled onto it`() {
        assertEquals(0f to 1f, FocusMap.toSensor(-1f, 2f, 0))
    }
}
