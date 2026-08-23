package com.homecam.app.net

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalAddress {

    /**
     * The LAN address to put in the pairing QR. Loopback and the various point-to-point
     * interfaces are useless to a viewer on the other side of the room, so they are
     * skipped; a Tailscale interface is a valid answer when there is no Wi-Fi one.
     */
    fun best(): String? {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface -> iface.inetAddresses.asSequence().map { iface.name to it } }
            .filter { (_, address) -> address is Inet4Address && !address.isLoopbackAddress }
            .toList()

        val preferredOrder = listOf("wlan", "eth", "rmnet", "tun", "tailscale")
        return candidates
            .minByOrNull { (name, _) ->
                preferredOrder.indexOfFirst { name.startsWith(it) }.let { if (it < 0) 99 else it }
            }
            ?.second?.hostAddress
    }
}
