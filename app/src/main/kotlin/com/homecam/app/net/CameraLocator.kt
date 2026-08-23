package com.homecam.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket

data class CameraAddress(val host: String, val port: Int)

/**
 * Finds where a pinned camera currently lives. The mDNS advertisement is deliberately
 * anonymous (§2.4), so discovery cannot say *which* camera a service is — the pinned
 * certificate does. Every candidate is probed by completing a TLS handshake against the
 * pin, and the one that succeeds is the camera; the rest are strangers.
 */
class CameraLocator(private val context: Context) {

    /**
     * Tried in order of how likely each is to be both correct and cheap: the address that
     * worked last time, then whatever is on this LAN, then the remote address the user
     * configured. The remote one goes last because on a tailnet it will often succeed from
     * the sofa too, and routing local traffic over an overlay for no reason is a
     * needlessly slow way to be right.
     */
    suspend fun locate(
        pinnedIdentitySpki: ByteArray,
        lastHost: String,
        lastPort: Int,
        remoteHost: String = "",
        remotePort: Int = 0,
        discoveryTimeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    ): CameraAddress? {
        if (lastHost.isNotBlank() && probe(lastHost, lastPort, pinnedIdentitySpki)) {
            return CameraAddress(lastHost, lastPort)
        }

        val discovered = withTimeoutOrNull(discoveryTimeoutMs) {
            MdnsDiscovery(context).discover().take(MAX_CANDIDATES).toList()
        }.orEmpty()
        discovered.firstOrNull { probe(it.host, it.port, pinnedIdentitySpki) }
            ?.let { return CameraAddress(it.host, it.port) }

        if (remoteHost.isNotBlank()) {
            val port = remotePort.takeIf { it > 0 } ?: Signaling.DEFAULT_PORT
            if (probe(remoteHost, port, pinnedIdentitySpki)) return CameraAddress(remoteHost, port)
        }
        return null
    }

    /**
     * A completed handshake against the pinned key is proof enough to *try* the
     * address. It is not proof of identity on its own — the signed challenge-response
     * still runs — but it is what makes probing strangers safe: a service that is not the
     * camera fails here, before anything is sent.
     */
    private suspend fun probe(host: String, port: Int, pinnedIdentitySpki: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val factory = PinnedTls.clientContext(pinnedIdentitySpki).socketFactory
                (factory.createSocket() as SSLSocket).use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = CONNECT_TIMEOUT_MS
                    socket.startHandshake()
                    true
                }
            }.getOrDefault(false)
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
        const val DISCOVERY_TIMEOUT_MS = 4_000L
        const val MAX_CANDIDATES = 8
    }
}
