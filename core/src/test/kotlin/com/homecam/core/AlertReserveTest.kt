package com.homecam.core

import com.homecam.core.crypto.AlertReserve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertReserveTest {

    @Test
    fun `an unknown budget lets everything through`() {
        assertTrue(AlertReserve.allowsOrdinary(null, 3))
    }

    @Test
    fun `a server reporting no limit gives no budget`() {
        assertNull(AlertReserve.remaining(limitMessages = 0, messagesRemaining = 0))
    }

    @Test
    fun `a limited server reports what is left`() {
        assertEquals(42, AlertReserve.remaining(limitMessages = 250, messagesRemaining = 42))
    }

    @Test
    fun `an ordinary alert may take the budget down to the reserve`() {
        assertTrue(AlertReserve.allowsOrdinary(remaining = 11, cost = 1))
    }

    @Test
    fun `an ordinary alert may not cut into the reserve`() {
        assertFalse(AlertReserve.allowsOrdinary(remaining = 10, cost = 1))
        assertFalse(AlertReserve.allowsOrdinary(remaining = 0, cost = 1))
    }

    @Test
    fun `the cost is every recipient on the endpoint`() {
        assertFalse(AlertReserve.allowsOrdinary(remaining = 12, cost = 3))
        assertTrue(AlertReserve.allowsOrdinary(remaining = 13, cost = 3))
    }
}
