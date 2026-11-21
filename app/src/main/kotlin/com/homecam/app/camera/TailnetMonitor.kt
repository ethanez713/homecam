package com.homecam.app.camera

import android.content.Context
import com.homecam.app.AppGraph
import com.homecam.core.health.TailnetAddress
import com.homecam.core.health.TailnetAlert
import com.homecam.core.health.TailnetAlertPolicy
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Reads the camera phone's own Tailscale and feeds [TailnetAlertPolicy]. Present means
 * an up interface named `tun*` or `tailscale*` that carries a tailnet address. If the
 * interfaces cannot be listed, the sample is skipped: not knowing is not the same as
 * absent. The policy's seen and announced-down flags are stored in a private
 * preferences file.
 */
class TailnetMonitor(
    private val graph: AppGraph,
    private val onAlert: (TailnetAlert) -> Unit,
) {
    private val prefs = graph.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var storedSeen = prefs.getBoolean(KEY_SEEN, false)
    private var storedAnnouncedDown = prefs.getBoolean(KEY_ANNOUNCED_DOWN, false)
    private val policy = TailnetAlertPolicy(seen = storedSeen, announcedDown = storedAnnouncedDown)
    private var reportedPresent: Boolean? = null

    fun sample() {
        val present = readPresent() ?: return
        if (reportedPresent != present) {
            reportedPresent = present
            graph.diagnostics.tailnet(present)
        }
        val alert = policy.onSample(present, System.currentTimeMillis())
        persist()
        if (alert != null) onAlert(alert)
    }

    private fun persist() {
        if (policy.seen == storedSeen && policy.announcedDown == storedAnnouncedDown) return
        // commit, not apply: a kill before this is on disk would repeat an outage.
        val written = prefs.edit()
            .putBoolean(KEY_SEEN, policy.seen)
            .putBoolean(KEY_ANNOUNCED_DOWN, policy.announcedDown)
            .commit()
        if (!written) return
        storedSeen = policy.seen
        storedAnnouncedDown = policy.announcedDown
    }

    private fun readPresent(): Boolean? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        var present = false
        for (iface in interfaces) {
            if (!iface.isUp || !tailnetName(iface.name)) continue
            val addresses = iface.inetAddresses ?: continue
            for (address in addresses) {
                val bytes = address?.address ?: continue
                if (TailnetAddress.isTailnet(bytes)) {
                    present = true
                    break
                }
            }
            if (present) break
        }
        present
    } catch (_: SocketException) {
        null
    }

    private fun tailnetName(name: String?): Boolean =
        name != null && (name.startsWith("tun") || name.startsWith("tailscale"))

    private companion object {
        const val PREFS = "homecam.tailnet"
        const val KEY_SEEN = "seen"
        const val KEY_ANNOUNCED_DOWN = "announced_down"
    }
}
