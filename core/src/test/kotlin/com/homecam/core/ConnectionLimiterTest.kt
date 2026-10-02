package com.homecam.core

import com.homecam.core.signaling.ConnectionLimiter
import com.homecam.core.signaling.RejectionCoalescer
import com.homecam.core.signaling.RejectionCoalescer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLimiterTest {

    @Test
    fun `one address cannot hold more than its share`() {
        val limiter = ConnectionLimiter(maxTotal = 10, maxPerAddress = 2)
        assertTrue(limiter.tryAdmit("a"))
        assertTrue(limiter.tryAdmit("a"))
        assertFalse(limiter.tryAdmit("a"))
        assertTrue("another address is unaffected", limiter.tryAdmit("b"))
    }

    @Test
    fun `the total is capped and a release frees exactly one slot`() {
        val limiter = ConnectionLimiter(maxTotal = 3, maxPerAddress = 3)
        assertTrue(limiter.tryAdmit("a"))
        assertTrue(limiter.tryAdmit("b"))
        assertTrue(limiter.tryAdmit("c"))
        assertFalse(limiter.tryAdmit("d"))
        limiter.release("b")
        assertEquals(2, limiter.open())
        assertTrue(limiter.tryAdmit("d"))
        assertFalse(limiter.tryAdmit("e"))
    }

    @Test
    fun `releasing an address that was never admitted changes nothing`() {
        val limiter = ConnectionLimiter(maxTotal = 1, maxPerAddress = 1)
        limiter.release("ghost")
        assertTrue(limiter.tryAdmit("a"))
        assertFalse(limiter.tryAdmit("b"))
    }

    @Test
    fun `a flood from one source is one row and a count`() {
        val coalescer = RejectionCoalescer(windowMs = 60_000)
        val first = coalescer.onReject("10.0.0.5|hello", 0)
        val decisions = (1..500).map { coalescer.onReject("10.0.0.5|hello", it * 100L) }
        assertEquals(Decision.Record, first)
        assertEquals(Decision.Suppress(startsWindow = true), decisions.first())
        assertTrue(decisions.drop(1).all { it == Decision.Suppress(startsWindow = false) })
        assertEquals(500, coalescer.takeSuppressed("10.0.0.5|hello"))
        assertEquals(0, coalescer.takeSuppressed("10.0.0.5|hello"))
    }

    @Test
    fun `different sources and stages are counted separately so a genuine rejection stays legible`() {
        val coalescer = RejectionCoalescer(windowMs = 60_000)
        coalescer.onReject("10.0.0.5|hello", 0)
        repeat(50) { coalescer.onReject("10.0.0.5|hello", 10L + it) }
        assertEquals(Decision.Record, coalescer.onReject("10.0.0.9|hello", 100))
        assertEquals(Decision.Record, coalescer.onReject("10.0.0.5|auth", 100))
    }

    @Test
    fun `a refusal after the window records again`() {
        val coalescer = RejectionCoalescer(windowMs = 1_000)
        coalescer.onReject("k", 0)
        coalescer.onReject("k", 500)
        assertEquals(Decision.Record, coalescer.onReject("k", 1_500))
    }
}
