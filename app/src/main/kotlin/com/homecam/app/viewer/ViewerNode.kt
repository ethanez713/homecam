package com.homecam.app.viewer

import com.homecam.app.AppGraph
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

    suspend fun pin(invite: PairingInvite) {
        val camera = IdentityKey(invite.cameraPublicKey.toByteArray())
        cameras.upsert(
            CameraEntity(
                fingerprint = camera.shortFingerprint,
                publicKey = camera.spki,
                certFingerprint = invite.certFingerprint.toByteArray(),
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

    suspend fun seen(fingerprint: String, host: String, port: Int) =
        cameras.touch(fingerprint, host, port, System.currentTimeMillis())
}
