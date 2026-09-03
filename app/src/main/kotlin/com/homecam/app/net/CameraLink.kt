package com.homecam.app.net

import com.homecam.core.rpc.Controls
import com.homecam.core.rpc.RpcPeer
import java.io.File
import com.homecam.proto.Alias
import com.homecam.proto.Armed
import com.homecam.proto.AlertRegistration
import com.homecam.proto.Capabilities
import com.homecam.proto.AuditQuery
import com.homecam.proto.ClipData
import com.homecam.proto.ClipDelete
import com.homecam.proto.ClipList
import com.homecam.proto.ClipPin
import com.homecam.proto.ManualRecord
import com.homecam.proto.DetectionMode
import com.homecam.proto.Microphone
import com.homecam.proto.RecordingMode
import com.homecam.proto.RecordingModeSetting
import com.homecam.proto.RevokeViewer
import com.homecam.proto.RpcMessage
import com.homecam.proto.RuntimeStatus
import com.homecam.proto.SettingsSnapshot
import com.homecam.proto.Thumbnail
import com.homecam.proto.ViewerList

/**
 * Typed calls the viewer makes against a camera. Thin on purpose — it exists so screens
 * name a method instead of assembling service/control pairs, not to add a layer.
 */
class CameraLink(private val peer: RpcPeer) {

    suspend fun runtimeStatus(): RuntimeStatus =
        RuntimeStatus.parseFrom(peer.request(RpcMessage.Service.STATUS, Controls.Status.RUNTIME))

    /**
     * What the camera supports. Null when the camera is too old to answer — the dispatcher
     * drops the unknown service silently, so this times out rather than erroring, and the
     * caller then leaves every control available rather than hiding all of them.
     */
    suspend fun capabilities(): Capabilities? = runCatching {
        Capabilities.parseFrom(
            peer.request(
                RpcMessage.Service.CAPABILITY,
                Controls.Capability.EXCHANGE,
                timeoutMs = CAPABILITY_TIMEOUT_MS,
            )
        )
    }.getOrNull()

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

    suspend fun setRecordingMode(mode: RecordingMode): SettingsSnapshot = SettingsSnapshot.parseFrom(
        peer.request(
            RpcMessage.Service.SETTINGS,
            Controls.Settings.RECORDING_MODE,
            RecordingModeSetting.newBuilder().setMode(mode).build().toByteString(),
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

    /**
     * Alerts are off until this says otherwise (§2.9). The camera stores the public key
     * and topic; it never learns the private half, so it can address this phone without
     * being able to read what it sends.
     */
    suspend fun registerAlerts(
        alertPublicKey: ByteArray,
        topic: String,
        endpoint: String,
        enabled: Boolean,
        sessionAlerts: Boolean = true,
        detectionAlerts: Boolean = true,
    ) {
        peer.request(
            RpcMessage.Service.ACCESS,
            Controls.Access.ALERT_REGISTRATION,
            AlertRegistration.newBuilder()
                .setAlertPublicKey(com.google.protobuf.ByteString.copyFrom(alertPublicKey))
                .setTopic(topic)
                .setEndpoint(endpoint)
                .setEnabled(enabled)
                .setSessionAlerts(sessionAlerts)
                .setDetectionAlerts(detectionAlerts)
                .build()
                .toByteString(),
        )
    }

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

    suspend fun clips(sinceMs: Long = 0, limit: Int = 100): ClipList = ClipList.parseFrom(
        peer.request(
            RpcMessage.Service.MEDIA,
            Controls.Media.CLIP_LIST,
            ClipList.newBuilder().setSinceMs(sinceMs).setLimit(limit).build().toByteString(),
        )
    )

    /** The clip's pre-generated thumbnail, or null if the camera has none for it. */
    suspend fun thumbnail(id: String): ByteArray? = runCatching {
        Thumbnail.parseFrom(
            peer.request(
                RpcMessage.Service.MEDIA,
                Controls.Media.THUMBNAIL,
                Thumbnail.newBuilder().setClipId(id).build().toByteString(),
            )
        ).jpeg.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    suspend fun pinClip(id: String, pinned: Boolean) {
        peer.request(
            RpcMessage.Service.MEDIA,
            Controls.Media.CLIP_PIN,
            ClipPin.newBuilder().setId(id).setPinned(pinned).build().toByteString(),
        )
    }

    suspend fun deleteClip(id: String): Boolean = ClipDelete.parseFrom(
        peer.request(
            RpcMessage.Service.MEDIA,
            Controls.Media.CLIP_DELETE,
            ClipDelete.newBuilder().setId(id).build().toByteString(),
        )
    ).deleted

    /** Streams a clip into [destination]. Chunked because a clip does not fit one frame. */
    suspend fun downloadClip(id: String, destination: File) {
        destination.parentFile?.mkdirs()
        destination.outputStream().use { out ->
            peer.requestStream(
                RpcMessage.Service.MEDIA,
                Controls.Media.CLIP_DATA,
                ClipData.newBuilder().setClipId(id).build().toByteString(),
                timeoutMs = CLIP_DOWNLOAD_TIMEOUT_MS,
            ) { chunk -> out.write(ClipData.parseFrom(chunk).chunk.toByteArray()) }
        }
    }

    suspend fun recordManually(seconds: Int, start: Boolean = true) {
        peer.request(
            RpcMessage.Service.SESSION,
            Controls.Session.MANUAL_RECORD,
            ManualRecord.newBuilder().setRecording(start).setSeconds(seconds).build().toByteString(),
        )
    }

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
        const val CLIP_DOWNLOAD_TIMEOUT_MS = 120_000L
        // Short, because an old camera that cannot answer expresses that as a timeout.
        const val CAPABILITY_TIMEOUT_MS = 5_000L
    }
}
