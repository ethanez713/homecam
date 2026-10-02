package com.homecam.core

import com.homecam.core.detect.AlertThrottle
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertThrottleTest {

    private val throttle = AlertThrottle(quietMs = 300_000)

    @Test
    fun `someone who stays in view alerts once, however long they stay`() {
        val alerts = (0L..1_800_000L step 400).count { throttle.shouldAlert(listOf("person"), it) }

        assertEquals(1, alerts)
    }

    @Test
    fun `coming back alerts again only after the quiet period`() {
        assertEquals(true, throttle.shouldAlert(listOf("person"), 0))
        assertEquals(false, throttle.shouldAlert(listOf("person"), 299_999))
        assertEquals(true, throttle.shouldAlert(listOf("person"), 299_999 + 300_000))
    }

    @Test
    fun `a second person alerts once more, and the count moving after that does not`() {
        val two = listOf("person", "person")
        assertEquals(true, throttle.shouldAlert(listOf("person"), 0))
        assertEquals(true, throttle.shouldAlert(two, 400))
        assertEquals(false, throttle.shouldAlert(listOf("person"), 800))
        assertEquals(false, throttle.shouldAlert(two, 1_200))
        assertEquals(false, throttle.shouldAlert(two + "person", 1_600))
    }

    @Test
    fun `a visit that opens with two people is one alert`() {
        assertEquals(true, throttle.shouldAlert(listOf("person", "person"), 0))
        assertEquals(false, throttle.shouldAlert(listOf("person", "person"), 400))
    }

    @Test
    fun `a new category alerts while another is already in view`() {
        throttle.shouldAlert(listOf("person"), 0)

        assertEquals(true, throttle.shouldAlert(listOf("person", "pet"), 400))
        assertEquals(false, throttle.shouldAlert(listOf("person", "pet"), 800))
    }
}
