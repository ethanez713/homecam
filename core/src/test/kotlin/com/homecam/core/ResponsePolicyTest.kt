package com.homecam.core

import com.homecam.core.response.ResponsePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsePolicyTest {

    private val interval = 30
    private val intervalMs = interval * 60_000L

    @Test
    fun `a reminder is due only after the interval, and only while armed`() {
        assertFalse("not due before the interval",
            ResponsePolicy.reminderDue(enabled = true, armed = true, intervalMinutes = interval, lastRemindedMs = 0, nowMs = intervalMs - 1))
        assertTrue("due at the interval",
            ResponsePolicy.reminderDue(enabled = true, armed = true, intervalMinutes = interval, lastRemindedMs = 0, nowMs = intervalMs))
        assertFalse("suppressed while disarmed",
            ResponsePolicy.reminderDue(enabled = true, armed = false, intervalMinutes = interval, lastRemindedMs = 0, nowMs = intervalMs * 10))
        assertFalse("suppressed while disabled",
            ResponsePolicy.reminderDue(enabled = false, armed = true, intervalMinutes = interval, lastRemindedMs = 0, nowMs = intervalMs * 10))
    }

    @Test
    fun `auto spotlight needs enabled, a flash, and darkness`() {
        assertTrue(ResponsePolicy.autoSpotlightEngages(enabled = true, hasFlash = true, isDark = true))
        assertFalse("no flash", ResponsePolicy.autoSpotlightEngages(enabled = true, hasFlash = false, isDark = true))
        assertFalse("daylight", ResponsePolicy.autoSpotlightEngages(enabled = true, hasFlash = true, isDark = false))
        assertFalse("disabled", ResponsePolicy.autoSpotlightEngages(enabled = false, hasFlash = true, isDark = true))
    }
}
