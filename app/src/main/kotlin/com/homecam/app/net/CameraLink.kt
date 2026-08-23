package com.homecam.app.net

import com.homecam.core.rpc.Controls
import com.homecam.core.rpc.RpcPeer
import com.homecam.proto.Alias
import com.homecam.proto.Armed
import com.homecam.proto.AuditQuery
import com.homecam.proto.DetectionMode
import com.homecam.proto.Microphone
import com.homecam.proto.RevokeViewer
import com.homecam.proto.RpcMessage
import com.homecam.proto.RuntimeStatus
import com.homecam.proto.SettingsSnapshot
import com.homecam.proto.ViewerList

/**
 * Typed calls the viewer makes against a camera. Thin on purpose — it exists so screens
 * name a method instead of assembling service/control pairs, not to add a layer.
 */
class CameraLink(private val peer: RpcPeer) {

    suspend fun runtimeStatus(): RuntimeStatus =
        RuntimeStatus.parseFrom(peer.request(RpcMessage.Service.STATUS, Controls.Status.RUNTIME))

    suspend fun settings(): SettingsSnapshot =
        SettingsSnapshot.parseFrom(peer.request(RpcMessage.Service.SETTINGS, Controls.Settings.SNAPSHOT))

    suspend fun setArmed(armed: Boolean): SettingsSnapshot = SettingsSnapshot.parseFrom(
        peer.request(
            RpcMessage.Service.SETTINGS,
            Controls.Settings.ARMED,
            Armed.newBuilder().setArmed(armed).build().toByteString(),
        )
    )

    suspend fun setAlias(alias: String): SettingsSnapshot = SettingsSnapshot.parseFrom(
        peer.request(
            RpcMessage.Service.SETTINGS,
            Controls.Settings.ALIAS,
            Alias.newBuilder().setAlias(alias).build().toByteString(),
        )
    )

    suspend fun setMicrophone(enabled: Boolean): SettingsSnapshot = SettingsSnapshot.parseFrom(
        peer.request(
            RpcMessage.Service.SETTINGS,
            Controls.Settings.MICROPHONE,
            Microphone.newBuilder().setEnabled(enabled).build().toByteString(),
        )
    )

    suspend fun setDetectionMode(
        person: Boolean,
        vehicle: Boolean,
        pet: Boolean,
        packages: Boolean,
    ): SettingsSnapshot = SettingsSnapshot.parseFrom(
        peer.request(
            RpcMessage.Service.SETTINGS,
            Controls.Settings.DETECTION_MODE,
            DetectionMode.newBuilder()
                .setPerson(person)
                .setVehicle(vehicle)
                .setPet(pet)
                .setPackage(packages)
                .build()
                .toByteString(),
        )
    )

    suspend fun viewers(): ViewerList =
        ViewerList.parseFrom(peer.request(RpcMessage.Service.ACCESS, Controls.Access.VIEWER_LIST))

    suspend fun revoke(fingerprint: String): Boolean = RevokeViewer.parseFrom(
        peer.request(
            RpcMessage.Service.ACCESS,
            Controls.Access.REVOKE_VIEWER,
            RevokeViewer.newBuilder().setFingerprint(fingerprint).build().toByteString(),
        )
    ).revoked

    suspend fun revokeAll(): Boolean = RevokeViewer.parseFrom(
        peer.request(
            RpcMessage.Service.ACCESS,
            Controls.Access.REVOKE_VIEWER,
            RevokeViewer.newBuilder().setAll(true).build().toByteString(),
        )
    ).revoked

    suspend fun audit(sinceMs: Long = 0, limit: Int = 200): AuditQuery = AuditQuery.parseFrom(
        peer.request(
            RpcMessage.Service.AUDIT,
            Controls.Audit.QUERY,
            AuditQuery.newBuilder().setSinceMs(sinceMs).setLimit(limit).build().toByteString(),
            timeoutMs = AUDIT_TIMEOUT_MS,
        )
    )

    private companion object {
        // Verifying the chain rehashes the whole log, which on a camera that has been up
        // for weeks is thousands of rows.
        const val AUDIT_TIMEOUT_MS = 30_000L
    }
}
