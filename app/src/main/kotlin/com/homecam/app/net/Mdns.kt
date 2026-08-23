package com.homecam.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.security.SecureRandom

/**
 * Discovery advertises a service type, a port, and an opaque instance id that rotates on
 * every start — nothing else (§2.4). A LAN scanner learns that some service exists here
 * and no more: no camera name, no account, no device identifier. The camera answers
 * meaningfully only to an allowlisted key, so discovery is a convenience, not a gate.
 */
class MdnsAdvertiser(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null

    fun start(port: Int) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = opaqueInstanceId()
            serviceType = Signaling.MDNS_SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertising as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    private fun opaqueInstanceId(): String {
        val bytes = ByteArray(8).also(SecureRandom()::nextBytes)
        return "hc-" + bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object { const val TAG = "MdnsAdvertiser" }
}

data class DiscoveredCamera(val instanceId: String, val host: String, val port: Int)

/**
 * Browses for cameras on the LAN. A discovered address is only ever a *hint* about where
 * a pinned camera moved to — nothing here decides whether to trust it.
 */
class MdnsDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discover(): Flow<DiscoveredCamera> = callbackFlow {
        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    resolving = false
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    info.host?.hostAddress?.let {
                        trySend(DiscoveredCamera(info.serviceName, it, info.port))
                    }
                    resolving = false
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("mDNS discovery failed: $errorCode"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                // NsdManager only tolerates one resolve at a time; queueing is not an
                // optimisation, it is the difference between finding one camera and none.
                resolveQueue += info
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }

        nsd.discoverServices(Signaling.MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
}
