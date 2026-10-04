package com.homecam.core

import com.homecam.core.diagnostics.PeerPath
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerPathTest {

    @Test
    fun `the tailnet is 100_64_0_0 through 10 and nothing outside it`() {
        assertEquals(PeerPath.TAILNET, PeerPath.classify("100.64.0.0"))
        assertEquals(PeerPath.TAILNET, PeerPath.classify("100.64.0.1"))
        assertEquals(PeerPath.TAILNET, PeerPath.classify("100.127.255.255"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("100.63.255.255"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("100.128.0.0"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("100.255.1.1"))
    }

    @Test
    fun `rfc1918 is lan and a public address is other`() {
        assertEquals(PeerPath.LAN, PeerPath.classify("10.0.0.1"))
        assertEquals(PeerPath.LAN, PeerPath.classify("10.255.255.255"))
        assertEquals(PeerPath.LAN, PeerPath.classify("172.16.0.1"))
        assertEquals(PeerPath.LAN, PeerPath.classify("172.31.255.255"))
        assertEquals(PeerPath.LAN, PeerPath.classify("192.168.1.20"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("172.15.255.255"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("172.32.0.1"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("192.167.1.1"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("192.169.1.1"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("11.0.0.1"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("8.8.8.8"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("127.0.0.1"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("169.254.1.1"))
    }

    @Test
    fun `an ipv4-mapped address is classified as the ipv4 peer it carries`() {
        assertEquals(PeerPath.TAILNET, PeerPath.classify("::ffff:100.77.1.5"))
        assertEquals(PeerPath.TAILNET, PeerPath.classify("::FFFF:100.64.0.1"))
        assertEquals(PeerPath.LAN, PeerPath.classify("::ffff:192.168.1.20"))
        assertEquals(PeerPath.TAILNET, PeerPath.classify("::ffff:6440:1"))
        assertEquals(PeerPath.TAILNET, PeerPath.classify("0:0:0:0:0:ffff:6440:1"))
        assertEquals(PeerPath.LAN, PeerPath.classify("0:0:0:0:0:ffff:c0a8:114"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("::1"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("fd7a:115c:a1e0::1"))
    }

    @Test
    fun `brackets a zone and blank text do not change the class or invent one`() {
        assertEquals(PeerPath.LAN, PeerPath.classify("[192.168.0.5]"))
        assertEquals(PeerPath.LAN, PeerPath.classify("10.1.2.3%eth0"))
        assertEquals(PeerPath.TAILNET, PeerPath.classify("  100.64.8.8  "))
        assertEquals(PeerPath.OTHER, PeerPath.classify(null))
        assertEquals(PeerPath.OTHER, PeerPath.classify(""))
        assertEquals(PeerPath.OTHER, PeerPath.classify("   "))
        assertEquals(PeerPath.OTHER, PeerPath.classify("unknown"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("192.168.1"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("192.168.1.256"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("192.168.1.1.1"))
        assertEquals(PeerPath.OTHER, PeerPath.classify("::ffff:192.168.1.256"))
    }
}
