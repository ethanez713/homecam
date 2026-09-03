package com.homecam.app.camera

import com.google.protobuf.ByteString
import com.homecam.app.AppGraph
import com.homecam.app.data.ClipStore
import com.homecam.app.data.toProto
import com.homecam.core.rpc.Controls
import com.homecam.core.rpc.RpcException
import com.homecam.core.rpc.RpcPeer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.homecam.proto.AlertRegistration
import com.homecam.proto.Alias
import com.homecam.proto.AutoSpotlight
import com.homecam.proto.Armed
import com.homecam.proto.AuditQuery
import com.homecam.proto.AuditEvent
import com.homecam.proto.BoundingBoxOverlay
import com.homecam.proto.ClipData
import com.homecam.proto.ClipDelete
import com.homecam.proto.ClipList
import com.homecam.proto.ClipPin
import com.homecam.proto.DateList
import com.homecam.proto.DetectionMode
import com.homecam.proto.DetectionReminder
import com.homecam.proto.DetectionSchedule
import com.homecam.proto.DetectionZone
import com.homecam.proto.Lens
import com.homecam.proto.LowLight
import com.homecam.proto.ManualRecord
import com.homecam.proto.Microphone
import com.homecam.proto.Orientation
import com.homecam.proto.PrivacyMask
import com.homecam.proto.PushToTalk
import com.homecam.proto.Resolution
import com.homecam.proto.RecordingModeSetting
import com.homecam.proto.RetentionPolicy
import com.homecam.proto.RpcError
import com.homecam.proto.RpcMessage
import com.homecam.proto.RevokeViewer
import com.homecam.proto.SessionConfig
import com.homecam.proto.Siren
import com.homecam.proto.Spotlight
import com.homecam.proto.SettingsSnapshot
import com.homecam.proto.Thumbnail
import com.homecam.proto.TimelineQuery
import com.homecam.proto.Torch
import com.homecam.proto.Viewer
import com.homecam.proto.ViewerList
import com.homecam.proto.Zoom

/**
 * The camera's half of the service registry (§4.2). Registration is per authenticated
 * connection, so a handler never has to ask who is calling — if it is running at all, the
 * caller already proved possession of an allowlisted identity key.
 */
class CameraServices(
    private val graph: AppGraph,
    private val node: CameraNode,
    private val status: RuntimeStatusProvider,
    private val media: MediaController?,
    private val clips: ClipStore?,
    private val viewerLabel: String,
    private val viewerFingerprint: String,
) {
    fun registerOn(peer: RpcPeer) {
        registerSession(peer)
        registerStatus(peer)
        registerSettings(peer)
        registerAccess(peer)
        registerAudit(peer)
        registerMedia(peer)
        registerCapability(peer)
    }

    private fun registerCapability(peer: RpcPeer) {
        peer.register(RpcMessage.Service.CAPABILITY, Controls.Capability.EXCHANGE) {
            CapabilityProvider.capabilities(graph.app).toByteString()
        }
    }

    private fun registerMedia(peer: RpcPeer) {
        peer.register(RpcMessage.Service.MEDIA, Controls.Media.CLIP_LIST) { request ->
            val query = ClipList.parseFrom(request.payload)
            val until = query.untilMs.takeIf { it > 0 } ?: System.currentTimeMillis()
            val limit = query.limit.takeIf { it in 1..MAX_CLIP_ROWS } ?: DEFAULT_CLIP_ROWS
            ClipList.newBuilder()
                .setSinceMs(query.sinceMs)
                .setUntilMs(until)
                .addAllClips(
                    graph.database.clips().between(query.sinceMs, until, limit).map { it.toProto() }
                )
                .build()
                .toByteString()
        }

        peer.register(RpcMessage.Service.MEDIA, Controls.Media.CLIP_DELETE) { request ->
            val ask = ClipDelete.parseFrom(request.payload)
            val deleted = clips().delete(ask.id)
            if (!deleted) throw RpcException(RpcError.Code.NOT_FOUND, "no clip ${ask.id}")
            graph.audit.record(
                AuditEvent.Kind.CLIP_DELETED,
                viewerFingerprint = viewerFingerprint,
                viewerLabel = viewerLabel,
                detail = ask.id,
            )
            ask.toBuilder().setDeleted(true).build().toByteString()
        }

        peer.register(RpcMessage.Service.MEDIA, Controls.Media.THUMBNAIL) { request ->
            val ask = Thumbnail.parseFrom(request.payload)
            val file = clips().thumbnail(ask.clipId)
                ?: throw RpcException(RpcError.Code.NOT_FOUND, "no thumbnail for ${ask.clipId}")
            Thumbnail.newBuilder()
                .setClipId(ask.clipId)
                .setJpeg(ByteString.copyFrom(file.readBytes()))
                .build()
                .toByteString()
        }

        peer.register(RpcMessage.Service.MEDIA, Controls.Media.CLIP_PIN) { request ->
            val ask = ClipPin.parseFrom(request.payload)
            clips().setPinned(ask.id, ask.pinned)
            ask.toByteString()
        }

        peer.register(RpcMessage.Service.MEDIA, Controls.Media.DATE_LIST) {
            val byDate = graph.database.clips().all()
                .groupingBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.startedAtMs), ZoneId.systemDefault()).toString() }
                .eachCount()
                .toSortedMap()
            DateList.newBuilder()
                .addAllDates(byDate.keys)
                .addAllCounts(byDate.values)
                .build()
                .toByteString()
        }

        peer.register(RpcMessage.Service.MEDIA, Controls.Media.TIMELINE) { request ->
            val query = TimelineQuery.parseFrom(request.payload)
            val until = query.untilMs.takeIf { it > 0 } ?: System.currentTimeMillis()
            TimelineQuery.newBuilder()
                .setSinceMs(query.sinceMs)
                .setUntilMs(until)
                .addAllEntries(
                    graph.database.clips().between(query.sinceMs, until, MAX_CLIP_ROWS)
                        .flatMap { clip ->
                            clip.labels.split(",").filter { it.isNotBlank() }.ifEmpty { listOf("motion") }
                                .map { label ->
                                    TimelineQuery.Entry.newBuilder()
                                        .setAtMs(clip.startedAtMs)
                                        .setClipId(clip.id)
                                        .setLabel(label)
                                        .build()
                                }
                        }
                )
                .build()
                .toByteString()
        }

        peer.register(RpcMessage.Service.MEDIA, Controls.Media.CLIP_DATA) { request ->
            // Streamed in chunks: a two-minute 720p clip is tens of megabytes and would
            // not fit one DataChannel message, let alone one WebSocket frame.
            val ask = ClipData.parseFrom(request.payload)
            val file = clips().file(ask.clipId)
                ?: throw RpcException(RpcError.Code.NOT_FOUND, "no clip ${ask.clipId}")

            graph.audit.record(
                AuditEvent.Kind.CLIP_VIEWED,
                viewerFingerprint = viewerFingerprint,
                viewerLabel = viewerLabel,
                detail = ask.clipId,
            )

            file.inputStream().use { stream ->
                stream.skip(ask.offset)
                var offset = ask.offset
                val buffer = ByteArray(CLIP_CHUNK_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    peer.sendStreamChunk(
                        request,
                        ClipData.newBuilder()
                            .setClipId(ask.clipId)
                            .setOffset(offset)
                            .setChunk(ByteString.copyFrom(buffer, 0, read))
                            .build()
                            .toByteString(),
                    )
                    offset += read
                }
            }
            ClipData.newBuilder().setClipId(ask.clipId).setEof(true).build().toByteString()
        }
    }

    private fun clips(): ClipStore = clips
        ?: throw RpcException(RpcError.Code.UNAVAILABLE, "the clip store is not running")

    private fun registerSession(peer: RpcPeer) {
        peer.register(RpcMessage.Service.SESSION, Controls.Session.CONFIG) { request ->
            // The first RPC of every session (§4.2): it is what actually starts the media.
            media().startSession(viewerFingerprint, SessionConfig.parseFrom(request.payload))
                .toByteString()
        }
        peer.register(RpcMessage.Service.STATUS, Controls.Status.SESSION) {
            media().sessionStatus(viewerFingerprint).toByteString()
        }
        peer.register(RpcMessage.Service.SESSION, Controls.Session.TORCH) { request ->
            media().setTorch(Torch.parseFrom(request.payload).on)
            request.payload
        }
        peer.register(RpcMessage.Service.SESSION, Controls.Session.ZOOM) { request ->
            media().setZoom(Zoom.parseFrom(request.payload).ratio)
            request.payload
        }
        peer.register(RpcMessage.Service.SESSION, Controls.Session.MANUAL_RECORD) { request ->
            val ask = ManualRecord.parseFrom(request.payload)
            media().recordManually(ask.seconds.takeIf { it > 0 } ?: 30, ask.recording)
            request.payload
        }
        peer.register(RpcMessage.Service.SESSION, Controls.Session.RESOLUTION) { request ->
            media().setResolution(Resolution.parseFrom(request.payload)).toByteString()
        }
        peer.register(RpcMessage.Service.SESSION, Controls.Session.LENS) { request ->
            media().setLens(Lens.parseFrom(request.payload).facing)
            request.payload
        }
        peer.register(RpcMessage.Service.SESSION, Controls.Session.PUSH_TO_TALK) { request ->
            media().setPushToTalk(PushToTalk.parseFrom(request.payload).active)
            request.payload
        }
        peer.register(RpcMessage.Service.SESSION, Controls.Session.ORIENTATION) { request ->
            media().setOrientation(Orientation.parseFrom(request.payload).degrees)
            media().sessionStatus(viewerFingerprint).toByteString()
        }
        peer.register(RpcMessage.Service.SESSION, Controls.Session.SIREN) { request ->
            val ask = Siren.parseFrom(request.payload)
            media().setSiren(ask.on, ask.seconds)
            request.payload
        }
        peer.register(RpcMessage.Service.SESSION, Controls.Session.SPOTLIGHT) { request ->
            media().setSpotlight(Spotlight.parseFrom(request.payload).on)
            request.payload
        }
        peer.register(RpcMessage.Service.SESSION, Controls.Session.SNAPSHOT) {
            val snapshot = media().snapshot()
            // A still of the room is an access to it, so it is logged like a clip view.
            graph.audit.record(
                AuditEvent.Kind.CLIP_VIEWED,
                viewerFingerprint = viewerFingerprint,
                viewerLabel = viewerLabel,
                detail = "snapshot",
            )
            snapshot.toByteString()
        }
    }

    private fun media(): MediaController = media
        ?: throw RpcException(RpcError.Code.UNAVAILABLE, "the camera pipeline is not running")

    private fun registerStatus(peer: RpcPeer) {
        peer.register(RpcMessage.Service.STATUS, Controls.Status.RUNTIME) {
            status.snapshot(clipBytes = graph.database.clips().all().sumOf { it.sizeBytes })
                .toByteString()
        }
    }

    private fun registerSettings(peer: RpcPeer) {
        peer.register(RpcMessage.Service.SETTINGS, Controls.Settings.SNAPSHOT) {
            graph.settings.current.toByteString()
        }
        settingSetter(peer, Controls.Settings.ARMED) { setArmed(Armed.parseFrom(it).armed) }
        settingSetter(peer, Controls.Settings.ALIAS) { setAlias(Alias.parseFrom(it).alias) }
        settingSetter(peer, Controls.Settings.MICROPHONE) { setMicrophone(Microphone.parseFrom(it).enabled) }
        settingSetter(peer, Controls.Settings.LOW_LIGHT) { setLowLight(LowLight.parseFrom(it).enabled) }
        settingSetter(peer, Controls.Settings.DETECTION_MODE) { setDetectionMode(DetectionMode.parseFrom(it)) }
        settingSetter(peer, Controls.Settings.DETECTION_SCHEDULE) { setDetectionSchedule(DetectionSchedule.parseFrom(it)) }
        settingSetter(peer, Controls.Settings.DETECTION_ZONE) { setDetectionZone(DetectionZone.parseFrom(it)) }
        settingSetter(peer, Controls.Settings.PRIVACY_MASK) { setPrivacyMask(PrivacyMask.parseFrom(it)) }
        settingSetter(peer, Controls.Settings.BOUNDING_BOX_OVERLAY) {
            setBoundingBoxOverlay(BoundingBoxOverlay.parseFrom(it).enabled)
        }
        settingSetter(peer, Controls.Settings.RETENTION) { setRetention(RetentionPolicy.parseFrom(it)) }
        settingSetter(peer, Controls.Settings.RECORDING_MODE) {
            setRecordingMode(RecordingModeSetting.parseFrom(it).mode)
        }
        settingSetter(peer, Controls.Settings.AUTO_SPOTLIGHT) {
            setAutoSpotlight(AutoSpotlight.parseFrom(it).enabled)
        }
        settingSetter(peer, Controls.Settings.DETECTION_REMINDER) {
            setDetectionReminder(DetectionReminder.parseFrom(it))
        }
    }

    /**
     * Every settings write is an audited event, because §2.7 counts changing what the
     * camera does as an access to it — arming, aliasing and privacy masks especially.
     */
    private fun settingSetter(
        peer: RpcPeer,
        control: Int,
        edit: SettingsSnapshot.Builder.(ByteString) -> Unit,
    ) {
        peer.register(RpcMessage.Service.SETTINGS, control) { request ->
            val updated = graph.settings.update { edit(request.payload) }
            if (control == Controls.Settings.PRIVACY_MASK) {
                media?.setPrivacyMask(updated.privacyMask)
            }
            graph.audit.record(
                if (control == Controls.Settings.PRIVACY_MASK) AuditEvent.Kind.PRIVACY_MASK_EDITED
                else AuditEvent.Kind.SETTINGS_CHANGED,
                viewerFingerprint = viewerFingerprint,
                viewerLabel = viewerLabel,
                detail = nameOfSetting(control),
            )
            updated.toByteString()
        }
    }

    private fun registerAccess(peer: RpcPeer) {
        peer.register(RpcMessage.Service.ACCESS, Controls.Access.VIEWER_LIST) {
            // §2.6: the roster is readable from the viewer app, so the camera can be
            // audited from the phone in your pocket rather than only from the hallway.
            val now = System.currentTimeMillis()
            ViewerList.newBuilder()
                .addAllViewers(
                    graph.database.viewers().all().map { row ->
                        Viewer.newBuilder()
                            .setPublicKey(ByteString.copyFrom(row.publicKey))
                            .setFingerprint(row.fingerprint)
                            .setLabel(row.label)
                            .setPairedAtMs(row.pairedAtMs)
                            .setLastSeenMs(row.lastSeenMs)
                            .setSessions30D(node.sessionsIn30d(row.fingerprint))
                            .setOnline(now - row.lastSeenMs < ONLINE_WINDOW_MS)
                            .build()
                    }
                )
                .build()
                .toByteString()
        }

        peer.register(RpcMessage.Service.ACCESS, Controls.Access.ALERT_REGISTRATION) { request ->
            val registration = AlertRegistration.parseFrom(request.payload)
            graph.database.viewers().setAlerts(
                fingerprint = viewerFingerprint,
                key = registration.alertPublicKey.toByteArray().takeIf { it.isNotEmpty() },
                topic = registration.topic,
                endpoint = registration.endpoint,
                enabled = registration.enabled,
                sessionAlerts = registration.sessionAlerts,
                detectionAlerts = registration.detectionAlerts,
            )
            graph.audit.record(
                AuditEvent.Kind.SETTINGS_CHANGED,
                viewerFingerprint = viewerFingerprint,
                viewerLabel = viewerLabel,
                detail = if (registration.enabled) "alerts on" else "alerts off",
            )
            registration.toByteString()
        }

        peer.register(RpcMessage.Service.ACCESS, Controls.Access.REVOKE_VIEWER) { request ->
            val ask = RevokeViewer.parseFrom(request.payload)
            if (ask.all) node.revokeAll() else node.revoke(ask.fingerprint)
            ask.toBuilder().setRevoked(true).build().toByteString()
        }
    }

    private fun registerAudit(peer: RpcPeer) {
        peer.register(RpcMessage.Service.AUDIT, Controls.Audit.QUERY) { request ->
            val query = AuditQuery.parseFrom(request.payload)
            val until = query.untilMs.takeIf { it > 0 } ?: System.currentTimeMillis()
            val limit = query.limit.takeIf { it in 1..MAX_AUDIT_ROWS } ?: DEFAULT_AUDIT_ROWS
            val kinds = query.kindsList.map { it.number }.toSet()
            val events = graph.audit.between(query.sinceMs, until, limit)
                .filter { kinds.isEmpty() || it.kindValue in kinds }
            val verification = graph.audit.verify()

            AuditQuery.newBuilder()
                .setSinceMs(query.sinceMs)
                .setUntilMs(until)
                .addAllEvents(events)
                .setChainValid(verification.valid)
                .setFirstBrokenSeq(verification.firstBrokenSeq.coerceAtLeast(0))
                .build()
                .toByteString()
        }
    }

    private fun nameOfSetting(control: Int): String = when (control) {
        Controls.Settings.ARMED -> "armed"
        Controls.Settings.ALIAS -> "alias"
        Controls.Settings.MICROPHONE -> "microphone"
        Controls.Settings.LOW_LIGHT -> "low light"
        Controls.Settings.DETECTION_MODE -> "detection mode"
        Controls.Settings.DETECTION_SCHEDULE -> "detection schedule"
        Controls.Settings.DETECTION_ZONE -> "detection zone"
        Controls.Settings.PRIVACY_MASK -> "privacy mask"
        Controls.Settings.BOUNDING_BOX_OVERLAY -> "bounding boxes"
        Controls.Settings.RETENTION -> "retention"
        Controls.Settings.RECORDING_MODE -> "recording mode"
        Controls.Settings.AUTO_SPOTLIGHT -> "auto spotlight"
        Controls.Settings.DETECTION_REMINDER -> "detection reminder"
        else -> throw RpcException(RpcError.Code.UNIMPLEMENTED, "unknown setting $control")
    }

    private companion object {
        const val ONLINE_WINDOW_MS = 60_000L
        const val DEFAULT_CLIP_ROWS = 100
        const val MAX_CLIP_ROWS = 1_000
        const val CLIP_CHUNK_BYTES = 48 * 1024
        const val DEFAULT_AUDIT_ROWS = 200
        const val MAX_AUDIT_ROWS = 2_000
    }
}
