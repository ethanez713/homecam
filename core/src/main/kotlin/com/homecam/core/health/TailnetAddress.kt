package com.homecam.core.health

/** Tailscale's own ranges: 100.64.0.0/10, and fd7a:115c:a1e0::/48. */
object TailnetAddress {
    fun isTailnet(address: ByteArray): Boolean = when (address.size) {
        4 -> (address[0].toInt() and 0xff) == 100 && (address[1].toInt() and 0xff) in 64..127
        16 -> hasIpv6Prefix(address)
        else -> false
    }

    private fun hasIpv6Prefix(address: ByteArray): Boolean {
        for (i in IPV6_PREFIX.indices) if (address[i] != IPV6_PREFIX[i]) return false
        return true
    }

    private val IPV6_PREFIX = byteArrayOf(
        0xfd.toByte(),
        0x7a.toByte(),
        0x11.toByte(),
        0x5c.toByte(),
        0xa1.toByte(),
        0xe0.toByte(),
    )
}
