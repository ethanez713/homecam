package com.homecam.app.viewer

import android.content.Context
import com.homecam.app.media.ViewerMediaSession
import com.homecam.app.net.SignalingClient
import com.homecam.core.rpc.Controls
import com.homecam.proto.Bye
import com.homecam.proto.Focus
import com.homecam.proto.Candidate
import com.homecam.proto.LiveLenses
import com.homecam.proto.Presence
import com.homecam.proto.RpcMessage
import com.homecam.proto.Sdp
import com.homecam.proto.SessionConfig
import com.homecam.proto.SessionStatus
import com.homecam.proto.SignalMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.VideoTrack

/**
 * Ties an authenticated [SignalingClient] to a media session. Nothing here decides trust:
 * by the time this exists the camera has already been proven, and the DTLS check inside
 * [ViewerMediaSession] is what carries that proof onto the media path (§2.5).
 */
class ViewerSession(
    private val context: Context,
    private val client: SignalingClient,
) {
    val link get() = client.link

    private val _videos = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    /** Video tracks keyed by lens id. An empty key is a track whose stream named no lens. */
    val videos: StateFlow<Map<String, VideoTrack>> = _videos.asStateFlow()

    private val sessionLock = Mutex()
    private var audio = true

    private val _presence = MutableStateFlow<Presence?>(null)
    val presence: StateFlow<Presence?> = _presence.asStateFlow()

    private val _ended = MutableStateFlow<Pair<Bye.Reason, String>?>(null)
    val ended: StateFlow<Pair<Bye.Reason, String>?> = _ended.asStateFlow()

    private val _manualRecordResult = MutableStateFlow<com.homecam.proto.ManualRecordResult?>(null)
    /** The id of the last manual clip the camera reported closed, pushed not polled. */
    val manualRecordResult: StateFlow<com.homecam.proto.ManualRecordResult?> = _manualRecordResult.asStateFlow()

    private val transport = object : ViewerMediaSession.Transport {
        override fun sendAnswer(sdp: Sdp) =
            client.send(SignalMessage.newBuilder().setAnswer(sdp).build())

        override fun sendCandidate(candidate: Candidate) =
            client.send(SignalMessage.newBuilder().setCandidate(candidate).build())

        override fun onVideoTrack(track: VideoTrack, lensId: String) {
            _videos.value = _videos.value + (lensId to track)
        }

        override fun tearDown(reason: Bye.Reason, detail: String) {
            _ended.value = reason to detail
            close()
        }
    }

    private var media = ViewerMediaSession(context, transport)

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
    suspend fun start(audio: Boolean = true): SessionStatus = sessionLock.withLock {
        this.audio = audio
        startLocked()
    }

    /**
     * A new secondary lens is a new set of tracks, so the session is negotiated again.
     * The peer connection is replaced first: an offer cannot be applied to the old one,
     * and its DTLS fingerprint was pinned to that connection.
     */
    suspend fun restart(): SessionStatus = sessionLock.withLock {
        _videos.value = emptyMap()
        delay(SINK_DETACH_MS)
        val retiring = media
        media = ViewerMediaSession(context, transport)
        retiring.close()
        startLocked()
    }

    /** Which lens tracks to keep sending. A lens left out stays recorded. */
    suspend fun setShownLenses(shown: List<String>) {
        client.rpc.request(
            RpcMessage.Service.SESSION,
            Controls.Session.LIVE_LENSES,
            LiveLenses.newBuilder().addAllShown(shown).build().toByteString(),
        )
    }

    private suspend fun startLocked(): SessionStatus = SessionStatus.parseFrom(
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

    /**
     * Turns the camera's picture a quarter-turn clockwise, for a phone propped on its side.
     * The camera applies it at capture, so recordings and detection turn with the live view.
     */
    suspend fun rotateQuarterTurn() {
        val status = com.homecam.proto.SessionStatus.parseFrom(
            client.rpc.request(RpcMessage.Service.STATUS, Controls.Status.SESSION, com.google.protobuf.ByteString.EMPTY)
        )
        client.rpc.request(
            RpcMessage.Service.SESSION, Controls.Session.ORIENTATION,
            com.homecam.proto.Orientation.newBuilder()
                .setDegrees((status.orientationDegrees + 90) % 360).build().toByteString(),
        )
    }

    suspend fun setZoom(ratio: Float) {
        client.rpc.request(
            RpcMessage.Service.SESSION, Controls.Session.ZOOM,
            com.homecam.proto.Zoom.newBuilder().setRatio(ratio).build().toByteString(),
        )
    }

    /** [x] and [y] are 0..1 on the upright picture. [auto] returns the camera to continuous autofocus. */
    suspend fun setFocus(x: Float, y: Float, auto: Boolean = false) {
        client.rpc.request(
            RpcMessage.Service.SESSION, Controls.Session.FOCUS,
            Focus.newBuilder().setX(x).setY(y).setAuto(auto).build().toByteString(),
        )
    }

    suspend fun setLiveResolution(height: Int) {
        val width = if (height == 0) 0 else height * 16 / 9
        client.rpc.request(
            RpcMessage.Service.SESSION, Controls.Session.RESOLUTION,
            com.homecam.proto.Resolution.newBuilder().setWidth(width).setHeight(height).build().toByteString(),
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
        _videos.value = emptyMap()
        media.close()
        client.close()
    }

    private companion object {
        // The camera has to bind CameraX and produce a first frame, which on a cold
        // pipeline is slower than an ordinary RPC.
        const val START_TIMEOUT_MS = 20_000L
        // Renderers drop their sinks on the next frame. Closing under a sink still
        // attached takes the process down inside libwebrtc.
        const val SINK_DETACH_MS = 50L
    }
}
