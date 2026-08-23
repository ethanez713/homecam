package com.homecam.app.viewer

import android.os.Build
import com.homecam.app.AppGraph
import com.homecam.app.net.CameraAddress
import com.homecam.app.net.CameraLocator
import com.homecam.app.net.SignalingClient
import com.homecam.app.data.CameraEntity
import com.homecam.core.crypto.IdentityKey
import com.homecam.proto.AuditEvent
import com.homecam.proto.PairingInvite
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Everything the viewer role owns that outlives a screen: which cameras it has pinned. */
class ViewerNode(private val graph: AppGraph) {

    private val cameras = graph.database.cameras()

    val paired: StateFlow<List<CameraEntity>> = cameras.observeAll()
        .stateIn(graph.scope, SharingStarted.Eagerly, emptyList())

    /**
     * Runs the pairing handshake over the wire and, if the camera accepts, pins it.
     *
     * Pinning happens before the user confirms the phrase, because by that point the
     * camera has already added this device to its allowlist — a viewer that could not
     * reconnect would have no way to undo that. The phrase is the check on what was
     * paired, and "these don't match" is answered by forgetting the camera and revoking
     * from the camera phone (§2.3).
     */
    suspend fun pair(invite: PairingInvite): String {
        val camera = IdentityKey(invite.cameraPublicKey.toByteArray())
        val client = SignalingClient(
            identity = graph.identity,
            camera = camera,
            deviceLabel = deviceLabel(),
            host = invite.host,
            port = invite.port,
            pairingToken = invite.token.toByteArray(),
        )
        val phrase = try {
            client.connect()
        } finally {
            client.close()
        }
        pin(invite)
        return phrase
    }

    fun deviceLabel(): String = "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}"

    suspend fun pin(invite: PairingInvite) {
        val camera = IdentityKey(invite.cameraPublicKey.toByteArray())
        cameras.upsert(
            CameraEntity(
                fingerprint = camera.shortFingerprint,
                publicKey = camera.spki,
                alias = invite.cameraAlias.ifBlank { "Camera" },
                lastHost = invite.host,
                lastPort = invite.port,
                pairedAtMs = System.currentTimeMillis(),
                lastSeenMs = System.currentTimeMillis(),
            )
        )
        graph.audit.record(
            AuditEvent.Kind.PAIRING_COMPLETED,
            viewerFingerprint = camera.shortFingerprint,
            viewerLabel = invite.cameraAlias,
            sourceIp = "${invite.host}:${invite.port}",
        )
    }

    suspend fun forget(fingerprint: String) {
        cameras.delete(fingerprint)
        graph.audit.record(AuditEvent.Kind.VIEWER_REVOKED, viewerFingerprint = fingerprint)
    }

    suspend fun byFingerprint(fingerprint: String): CameraEntity? = cameras.byFingerprint(fingerprint)

    suspend fun setRemoteAddress(fingerprint: String, host: String, port: Int) =
        cameras.setRemote(fingerprint, host.trim(), port)

    suspend fun seen(fingerprint: String, host: String, port: Int) =
        cameras.touch(fingerprint, host, port, System.currentTimeMillis())

    /**
     * Where a pinned camera is right now. A phone that moved to a new DHCP lease is the
     * normal case, not an error, so the last address is only a first guess.
     */
    suspend fun locate(camera: CameraEntity): CameraAddress? =
        CameraLocator(graph.app)
            .locate(
                pinnedIdentitySpki = camera.publicKey,
                lastHost = camera.lastHost,
                lastPort = camera.lastPort,
                remoteHost = camera.remoteHost,
                remotePort = camera.remotePort,
            )
            ?.also { seen(camera.fingerprint, it.host, it.port) }

    /** Opens an authenticated signaling connection to a camera that is already paired. */
    suspend fun connect(camera: CameraEntity): SignalingClient {
        val address = locate(camera) ?: throw IllegalStateException("camera not found on this network")
        val client = SignalingClient(
            identity = graph.identity,
            camera = IdentityKey(camera.publicKey),
            deviceLabel = deviceLabel(),
            host = address.host,
            port = address.port,
        )
        client.connect()
        return client
    }
}
