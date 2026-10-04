package com.homecam.core

import com.homecam.core.diagnostics.PeerPath
import com.homecam.core.diagnostics.RefusalLogLimiter
import com.homecam.core.diagnostics.RefusalLogLimiter.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class RefusalLogLimiterTest {

    @Test
    fun `a window records five refusals and rolls at sixty seconds`() {
        val limiter = RefusalLogLimiter()
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 0))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 1))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 2))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 3))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 4))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.LAN, "limit", 5))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.LAN, "limit", 59_999))
        assertEquals(Decision.Record(2), limiter.onRefusal(PeerPath.LAN, "limit", 60_000))
    }

    @Test
    fun `keys do not share a budget`() {
        val limiter = RefusalLogLimiter()
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 0))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 1))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 2))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 3))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 4))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.LAN, "limit", 5))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "path", 5))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.TAILNET, "limit", 5))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.LAN, "limit", 6))
    }

    @Test
    fun `the suppressed count is carried once and then only what the next window dropped`() {
        val limiter = RefusalLogLimiter()
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "flood", 0))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "flood", 1))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "flood", 2))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "flood", 3))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "flood", 4))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.OTHER, "flood", 10))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.OTHER, "flood", 11))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.OTHER, "flood", 12))
        assertEquals(Decision.Record(3), limiter.onRefusal(PeerPath.OTHER, "flood", 60_000))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "flood", 60_001))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "flood", 60_002))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "flood", 60_003))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "flood", 60_004))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.OTHER, "flood", 60_005))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.OTHER, "flood", 60_006))
        assertEquals(Decision.Record(2), limiter.onRefusal(PeerPath.OTHER, "flood", 120_000))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "flood", 120_001))
    }

    @Test
    fun `a long gap still records and still carries only the outstanding count`() {
        val limiter = RefusalLogLimiter()
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "text", 0))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "text", 1))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "text", 2))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "text", 3))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "text", 4))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.LAN, "text", 5))
        assertEquals(Decision.Record(1), limiter.onRefusal(PeerPath.LAN, "text", 1_000_000))
    }

    @Test
    fun `many keys stay within the cap and a quiet key is forgotten`() {
        val limiter = RefusalLogLimiter(maxPerWindow = 1, maxKeys = 2)
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 0))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.LAN, "limit", 1))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.TAILNET, "limit", 2))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.OTHER, "path", 3))
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "flood", 4))
        assertEquals(2, limiter.trackedKeys())
        assertEquals(Decision.Record(0), limiter.onRefusal(PeerPath.LAN, "limit", 5))
        assertEquals(Decision.Drop, limiter.onRefusal(PeerPath.LAN, "flood", 6))
        assertEquals(2, limiter.trackedKeys())
    }
}
