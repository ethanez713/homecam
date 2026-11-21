package com.homecam.core

import com.homecam.core.health.HealthAlertLabels
import com.homecam.core.health.HealthAlertPolicy
import com.homecam.core.health.TailnetAddress
import com.homecam.core.health.TailnetAlert
import com.homecam.core.health.TailnetAlertPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetAddressTest {

    @Test
    fun `both tailnet ranges match and the neighbours do not`() {
        assertTrue(TailnetAddress.isTailnet(v4(100, 64, 0, 0)))
        assertTrue(TailnetAddress.isTailnet(v4(100, 64, 0, 1)))
        assertTrue(TailnetAddress.isTailnet(v4(100, 127, 255, 255)))
        assertTrue(TailnetAddress.isTailnet(v6(0xfd7a, 0x115c, 0xa1e0, 0, 0, 0, 0, 0)))
        assertTrue(TailnetAddress.isTailnet(v6(0xfd7a, 0x115c, 0xa1e0, 0, 0, 0, 0, 1)))
        assertTrue(TailnetAddress.isTailnet(v6(0xfd7a, 0x115c, 0xa1e0, 0xffff, 0xffff, 0xffff, 0xffff, 0xffff)))

        assertFalse(TailnetAddress.isTailnet(v4(100, 63, 255, 255)))
        assertFalse(TailnetAddress.isTailnet(v4(100, 128, 0, 1)))
        assertFalse(TailnetAddress.isTailnet(v4(100, 255, 1, 1)))
        assertFalse(TailnetAddress.isTailnet(v4(101, 64, 0, 1)))
        assertFalse(TailnetAddress.isTailnet(v4(10, 64, 0, 1)))
        assertFalse(TailnetAddress.isTailnet(v6(0xfd7a, 0x115c, 0xa1e1, 0, 0, 0, 0, 1)))
        assertFalse(TailnetAddress.isTailnet(v6(0xfd7a, 0x115c, 0xa1df, 0, 0, 0, 0, 1)))
        assertFalse(TailnetAddress.isTailnet(v6(0xfd7a, 0x115d, 0xa1e0, 0, 0, 0, 0, 1)))
        assertFalse(TailnetAddress.isTailnet(v6(0, 0, 0, 0, 0, 0, 0, 1)))
        assertFalse(TailnetAddress.isTailnet(ByteArray(0)))
        assertFalse(TailnetAddress.isTailnet(v4(100, 64, 0, 1) + byteArrayOf(0)))
    }

    @Test
    fun `a sixteen-byte address is only the ula, not an ipv4-mapped tailnet address`() {
        assertFalse(TailnetAddress.isTailnet(v6(0, 0, 0, 0, 0, 0xffff, 0x6440, 1)))
    }

    private fun v4(a: Int, b: Int, c: Int, d: Int) =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    /** Eight hextets, as written in an IPv6 address. */
    private fun v6(vararg hextet: Int): ByteArray {
        require(hextet.size == 8)
        val out = ByteArray(16)
        for (i in hextet.indices) {
            out[i * 2] = (hextet[i] ushr 8).toByte()
            out[i * 2 + 1] = hextet[i].toByte()
        }
        return out
    }
}

class TailnetAlertPolicyTest {
    private val minute = 60_000L

    @Test
    fun `the grace is five minutes and the gap is the health alert gap`() {
        assertEquals(5 * minute, TailnetAlertPolicy.GRACE_MS)
        assertEquals(30 * minute, TailnetAlertPolicy.MIN_GAP_MS)
        assertEquals(HealthAlertPolicy.MIN_GAP_MS, TailnetAlertPolicy.MIN_GAP_MS)
    }

    @Test
    fun `an absence is silent until a tailnet has been seen`() {
        val policy = TailnetAlertPolicy()
        assertNull(policy.onSample(false, 0))
        assertNull(policy.onSample(false, 60 * minute))
        assertFalse(policy.seen)
        assertFalse(policy.announcedDown)
    }

    @Test
    fun `time absent before the first sighting does not count toward the grace`() {
        val policy = TailnetAlertPolicy()
        policy.onSample(false, 0)
        policy.onSample(false, 60 * minute)
        assertNull(policy.onSample(true, 61 * minute))
        assertTrue(policy.seen)
        policy.onSample(false, 62 * minute)
        assertNull(policy.onSample(false, 67 * minute - 1))
        assertEquals(TailnetAlert.DOWN, policy.onSample(false, 67 * minute))
    }

    @Test
    fun `five minutes of continuous absence announces down once`() {
        val policy = TailnetAlertPolicy(seen = true)
        assertNull(policy.onSample(false, 0))
        assertNull(policy.onSample(false, 5 * minute - 1))
        assertEquals(TailnetAlert.DOWN, policy.onSample(false, 5 * minute))
        assertTrue(policy.announcedDown)
        assertNull(policy.onSample(false, 5 * minute + 1))
        assertNull(policy.onSample(false, 80 * minute))
    }

    @Test
    fun `tailscale arriving inside the grace is not an outage`() {
        val policy = TailnetAlertPolicy(seen = true)
        assertNull(policy.onSample(false, 0))
        assertNull(policy.onSample(true, 5 * minute - 1))
        assertFalse(policy.announcedDown)
    }

    @Test
    fun `a brief return restarts the five minutes`() {
        val policy = TailnetAlertPolicy(seen = true)
        policy.onSample(false, 0)
        policy.onSample(true, 4 * minute)
        policy.onSample(false, 4 * minute)
        assertNull(policy.onSample(false, 9 * minute - 1))
        assertEquals(TailnetAlert.DOWN, policy.onSample(false, 9 * minute))
    }

    @Test
    fun `a return is announced only after a down was`() {
        val policy = TailnetAlertPolicy(seen = true)
        assertNull(policy.onSample(true, 0))
        policy.onSample(false, 1_000)
        assertNull(policy.onSample(true, 2_000))
        policy.onSample(false, 3_000)
        assertEquals(TailnetAlert.DOWN, policy.onSample(false, 3_000 + 5 * minute))
        assertEquals(TailnetAlert.BACK, policy.onSample(true, 3_000 + 6 * minute))
        assertFalse(policy.announcedDown)
        assertNull(policy.onSample(true, 3_000 + 7 * minute))
    }

    @Test
    fun `a down within thirty minutes is dropped for the whole absence, and so is its return`() {
        val policy = TailnetAlertPolicy(seen = true)
        policy.onSample(false, 0)
        assertEquals(TailnetAlert.DOWN, policy.onSample(false, 5 * minute))
        assertEquals(TailnetAlert.BACK, policy.onSample(true, 6 * minute))

        policy.onSample(false, 7 * minute)
        assertNull(policy.onSample(false, 12 * minute))
        assertFalse(policy.announcedDown)
        assertNull(policy.onSample(false, 80 * minute))
        assertNull(policy.onSample(true, 81 * minute))
    }

    @Test
    fun `an absence after a quiet one is announced once the gap has passed`() {
        val policy = TailnetAlertPolicy(seen = true)
        policy.onSample(false, 0)
        policy.onSample(false, 5 * minute)
        policy.onSample(true, 6 * minute)
        policy.onSample(false, 7 * minute)
        assertNull(policy.onSample(false, 12 * minute))
        assertNull(policy.onSample(true, 13 * minute))
        policy.onSample(false, 35 * minute)
        assertEquals(TailnetAlert.DOWN, policy.onSample(false, 40 * minute))
    }

    @Test
    fun `a down exactly thirty minutes after the previous one is announced`() {
        val policy = TailnetAlertPolicy(seen = true)
        policy.onSample(false, 0)
        assertEquals(TailnetAlert.DOWN, policy.onSample(false, 5 * minute))
        policy.onSample(true, 6 * minute)
        policy.onSample(false, 30 * minute)
        assertEquals(TailnetAlert.DOWN, policy.onSample(false, 35 * minute))
    }

    @Test
    fun `a down one millisecond inside the gap is not announced later either`() {
        val policy = TailnetAlertPolicy(seen = true)
        policy.onSample(false, 0)
        policy.onSample(false, 5 * minute)
        policy.onSample(true, 6 * minute)
        policy.onSample(false, 30 * minute - 1)
        assertNull(policy.onSample(false, 35 * minute - 1))
        assertNull(policy.onSample(false, 90 * minute))
        assertFalse(policy.announcedDown)
    }

    @Test
    fun `a reboot where the tailnet never returns still alerts when one was seen before`() {
        val policy = TailnetAlertPolicy(seen = true, announcedDown = false)
        assertNull(policy.onSample(false, 0))
        assertNull(policy.onSample(false, 5 * minute - 1))
        assertEquals(TailnetAlert.DOWN, policy.onSample(false, 5 * minute))
        assertTrue(policy.seen)
        assertTrue(policy.announcedDown)
    }

    @Test
    fun `a restart during an announced outage does not repeat the down`() {
        val first = TailnetAlertPolicy(seen = true)
        first.onSample(false, 0)
        assertEquals(TailnetAlert.DOWN, first.onSample(false, 5 * minute))

        val restarted = TailnetAlertPolicy(seen = first.seen, announcedDown = first.announcedDown)
        assertNull(restarted.onSample(false, 0))
        assertNull(restarted.onSample(false, 5 * minute))
        assertNull(restarted.onSample(false, 80 * minute))
        assertTrue(restarted.announcedDown)
        assertEquals(TailnetAlert.BACK, restarted.onSample(true, 81 * minute))
        assertFalse(restarted.announcedDown)
        assertTrue(restarted.seen)

        restarted.onSample(false, 82 * minute)
        assertEquals(TailnetAlert.DOWN, restarted.onSample(false, 87 * minute))
    }

    @Test
    fun `a restart that finds the tailnet already back announces the return`() {
        val policy = TailnetAlertPolicy(seen = true, announcedDown = true)
        assertEquals(TailnetAlert.BACK, policy.onSample(true, 0))
        assertFalse(policy.announcedDown)
        assertNull(policy.onSample(true, 1_000))
    }

    @Test
    fun `the tailnet labels read as the away-from-home sentences`() {
        assertEquals(
            "Tailscale is off. This camera can't be watched away from home.",
            HealthAlertLabels.describe("health:tailnet-down"),
        )
        assertEquals(
            "Tailscale is back. This camera can be watched away from home again.",
            HealthAlertLabels.describe("health:tailnet-back"),
        )
        assertEquals("health:tailnet-down", TailnetAlert.DOWN.label)
        assertEquals("health:tailnet-back", TailnetAlert.BACK.label)
    }
}
