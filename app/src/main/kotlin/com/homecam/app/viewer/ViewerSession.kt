package com.homecam.app.viewer

import android.content.Context
import com.homecam.app.media.ViewerMediaSession
import com.homecam.app.net.SignalingClient
import com.homecam.core.rpc.Controls
import com.homecam.proto.Bye
import com.homecam.proto.Candidate
import com.homecam.proto.Presence
import com.homecam.proto.RpcMessage
import com.homecam.proto.Sdp
import com.homecam.proto.SessionConfig
import com.homecam.proto.SessionStatus
import com.homecam.proto.SignalMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.VideoTrack

/**
 * Ties an authenticated [SignalingClient] to a media session. Nothing here decides trust:
 * by the time this exists the camera has already been proven, and the DTLS check inside
 * [ViewerMediaSession] is what carries that proof onto the media path (§2.5).
 */
class ViewerSession(
    context: Context,
    private val client: SignalingClient,
) {
    val link get() = client.link

    private val _video = MutableStateFlow<VideoTrack?>(null)
    val video: StateFlow<VideoTrack?> = _video.asStateFlow()

    private val _presence = MutableStateFlow<Presence?>(null)
    val presence: StateFlow<Presence?> = _presence.asStateFlow()

    private val _ended = MutableStateFlow<Pair<Bye.Reason, String>?>(null)
    val ended: StateFlow<Pair<Bye.Reason, String>?> = _ended.asStateFlow()

    private val _manualRecordResult = MutableStateFlow<com.homecam.proto.ManualRecordResult?>(null)
    /** The id of the last manual clip the camera reported closed, pushed not polled. */
    val manualRecordResult: StateFlow<com.homecam.proto.ManualRecordResult?> = _manualRecordResult.asStateFlow()

    private val media = ViewerMediaSession(
        context = context,
        transport = object : ViewerMediaSession.Transport {
            override fun sendAnswer(sdp: Sdp) =
                client.send(SignalMessage.newBuilder().setAnswer(sdp).build())

            override fun sendCandidate(candidate: Candidate) =
                client.send(SignalMessage.newBuilder().setCandidate(candidate).build())

            override fun onVideoTrack(track: VideoTrack) { _video.value = track }

            override fun tearDown(reason: Bye.Reason, detail: String) {
                _ended.value = reason to detail
                close()
            }
        },
    )

    init {
        client.rpc.register(RpcMessage.Service.SESSION, Controls.Session.MANUAL_RECORD_RESULT) { request ->
            _manualRecordResult.value = com.homecam.proto.ManualRecordResult.parseFrom(request.payload)
            null
        }
        client.listener = object : SignalingClient.Listener {
            override fun onAuthenticated(verificationPhrase: String) = Unit
            override fun onPresence(presence: Presence) { _presence.value = presence }
            override fun onOffer(sdp: Sdp) = media.onOffer(sdp)
            override fun onAnswer(sdp: Sdp) = Unit
            override fun onCandidate(candidate: Candidate) = media.onCandidate(candidate)
            override fun onClosed(reason: Bye.Reason, detail: String) {
                _ended.value = reason to detail
            }
        }
    }

    /** Asks the camera to start sending. The camera offers; this side answers. */
    suspend fun start(audio: Boolean = true): SessionStatus = SessionStatus.parseFrom(
        client.rpc.request(
            RpcMessage.Service.SESSION,
            Controls.Session.CONFIG,
            SessionConfig.newBuilder()
                .setVideo(true)
                .setAudio(audio)
                .setMode(SessionConfig.Mode.LIVE)
                .build()
                .toByteString(),
            timeoutMs = START_TIMEOUT_MS,
        )
    )

    suspend fun setTorch(on: Boolean) {
        client.rpc.request(
            RpcMessage.Service.SESSION, Controls.Session.TORCH,
            com.homecam.proto.Torch.newBuilder().setOn(on).build().toByteString(),
        )
    }

    suspend fun setZoom(ratio: Float) {
        client.rpc.request(
            RpcMessage.Service.SESSION, Controls.Session.ZOOM,
            com.homecam.proto.Zoom.newBuilder().setRatio(ratio).build().toByteString(),
        )
    }

    suspend fun setPushToTalk(active: Boolean) {
        media.setMicrophoneEnabled(active)
        client.rpc.request(
            RpcMessage.Service.SESSION, Controls.Session.PUSH_TO_TALK,
            com.homecam.proto.PushToTalk.newBuilder().setActive(active).build().toByteString(),
        )
    }

    fun close() {
        media.close()
        client.close()
    }

    private companion object {
        // The camera has to bind CameraX and produce a first frame, which on a cold
        // pipeline is slower than an ordinary RPC.
        const val START_TIMEOUT_MS = 20_000L
    }
}
