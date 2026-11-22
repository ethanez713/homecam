package com.homecam.core.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnlockAlertsTest {

    @Test
    fun `gesture alerts read as sentences and other labels read as nothing`() {
        assertEquals("door_unlocked", UnlockAlerts.DOOR_UNLOCKED)
        assertEquals("unlock_wrong_code", UnlockAlerts.WRONG_CODE)
        assertEquals("unlock_locked_out", UnlockAlerts.LOCKED_OUT)
        assertEquals("unlock_error", UnlockAlerts.ERROR)
        assertEquals(
            "The door was unlocked with the gesture code.",
            UnlockAlerts.describe(UnlockAlerts.DOOR_UNLOCKED),
        )
        assertEquals(
            "Someone tried the wrong gesture code.",
            UnlockAlerts.describe(UnlockAlerts.WRONG_CODE),
        )
        assertEquals(
            "Gesture unlock is locked out after too many wrong codes.",
            UnlockAlerts.describe(UnlockAlerts.LOCKED_OUT),
        )
        assertEquals("Gesture unlock failed.", UnlockAlerts.describe(UnlockAlerts.ERROR))
        assertNull(UnlockAlerts.describe("person"))
        assertNull(UnlockAlerts.describe("health:resumed"))
        assertNull(UnlockAlerts.describe(""))
    }
}
