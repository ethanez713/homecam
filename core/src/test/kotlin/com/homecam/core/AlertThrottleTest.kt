package com.homecam.core

import com.homecam.core.detect.AlertThrottle
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertThrottleTest {

    private val throttle = AlertThrottle(quietMs = 120_000)

    @Test
    fun `someone who stays in view alerts once, however long they stay`() {
        val alerts = (0L..600_000L step 400).count { throttle.shouldAlert(listOf("person"), it) }

        assertEquals(1, alerts)
    }

    @Test
    fun `coming back after the quiet period alerts again, sooner does not`() {
        assertEquals(true, throttle.shouldAlert(listOf("person"), 0))
        assertEquals(false, throttle.shouldAlert(listOf("person"), 119_999))
        assertEquals(true, throttle.shouldAlert(listOf("person"), 119_999 + 120_000))
    }

    @Test
    fun `a new category alerts while another is already in view`() {
        throttle.shouldAlert(listOf("person"), 0)

        assertEquals(true, throttle.shouldAlert(listOf("person", "pet"), 400))
        assertEquals(false, throttle.shouldAlert(listOf("person", "pet"), 800))
    }
}
