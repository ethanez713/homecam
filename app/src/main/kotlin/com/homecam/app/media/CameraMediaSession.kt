package com.homecam.app.media

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.homecam.core.signaling.DtlsCommitment
import com.homecam.core.signaling.DtlsFingerprint
import com.homecam.proto.Bye
import com.homecam.proto.Candidate
import com.homecam.proto.PrivacyMask
import com.homecam.proto.Sdp
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import java.util.UUID

/**
 * One live media session, from the camera's side. The camera offers, because it is the
 * side that has something to send.
 */
class CameraMediaSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val config: Config,
    private val transport: Transport,
) {
    data class Config(
        val width: Int = 1280,
        val height: Int = 720,
        val framerate: Int = 24,
        val audio: Boolean = true,
        val privacyMask: PrivacyMask = PrivacyMask.getDefaultInstance(),
    )

    interface Transport {
        fun sendOffer(sdp: Sdp)
        fun sendCandidate(candidate: Candidate)
        /** Called when the session must end, with the reason to show and to log. */
        fun tearDown(reason: Bye.Reason, detail: String)
    }

    val id: String = UUID.randomUUID().toString()

    private var current = config
    private val factory = WebRtc.factory(context)
    private val commitment = DtlsCommitment()

    private var connection: PeerConnection? = null
    private var capturer: CameraXVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoTrack: VideoTrack? = null
    private val maskProcessor = PrivacyMaskProcessor().apply { mask = config.privacyMask }
    private var audioTrack: AudioTrack? = null
    private var closed = false

    fun start() {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // No STUN or TURN server is configured, and that is the point: on the LAN and
            // over Tailscale the peers can already address each other, and a public STUN
            // server would be a third party learning when this camera is watched (§2.9).
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        connection = factory.createPeerConnection(rtcConfig, observer) ?: run {
            transport.tearDown(Bye.Reason.NO_FRAME, "could not create a peer connection")
            return
        }

        addVideo()
        if (config.audio) addAudio()
        createOffer()
    }

    private fun addVideo() {
        val helper = SurfaceTextureHelper.create("homecam-capture", WebRtc.eglBase.eglBaseContext)
        textureHelper = helper

        val source = factory.createVideoSource(false)
        source.setVideoProcessor(maskProcessor)
        val videoCapturer = CameraXVideoCapturer(context, lifecycleOwner)
        capturer = videoCapturer
        videoCapturer.initialize(helper, context, source.capturerObserver)
        videoCapturer.startCapture(current.width, current.height, current.framerate)

        videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, source).also {
            connection?.addTrack(it, listOf(STREAM_ID))
        }
    }

    private fun addAudio() {
        val source = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, source).also {
            connection?.addTrack(it, listOf(STREAM_ID))
        }
    }

    private fun createOffer() {
        val connection = connection ?: return
        connection.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    connection.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() = publishOffer(description)
                            override fun onSetFailure(error: String) =
                                transport.tearDown(Bye.Reason.NO_FRAME, "local description: $error")
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String) =
                    transport.tearDown(Bye.Reason.NO_FRAME, "offer: $error")
            },
            MediaConstraints(),
        )
    }

    private fun publishOffer(description: SessionDescription) {
        val fingerprint = DtlsFingerprint.parse(description.description)
        if (fingerprint == null) {
            // Refusing to offer an SDP we cannot commit to is the only safe answer: the
            // viewer would have nothing to check the media path against.
            transport.tearDown(Bye.Reason.NO_FRAME, "local sdp carries no DTLS fingerprint")
            return
        }
        transport.sendOffer(
            Sdp.newBuilder()
                .setSdp(description.description)
                .setDtlsFingerprint(fingerprint)
                .setSessionId(id)
                .build()
        )
    }

    fun onAnswer(answer: Sdp) {
        val connection = connection ?: return
        when (val result = commitment.check(answer.sdp, answer.dtlsFingerprint)) {
            DtlsCommitment.Result.ACCEPTED ->
                connection.setRemoteDescription(
                    object : SdpObserverAdapter() {
                        override fun onSetFailure(error: String) =
                            transport.tearDown(Bye.Reason.INCOMPATIBLE, "remote description: $error")
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, answer.sdp),
                )

            else -> transport.tearDown(Bye.Reason.FINGERPRINT_MISMATCH, result.name)
        }
    }

    fun onCandidate(candidate: Candidate) {
        connection?.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    fun setPrivacyMask(mask: PrivacyMask) { maskProcessor.mask = mask }

    /**
     * Changed on the capturer rather than by renegotiating: WebRTC adapts to a new frame
     * size on its own, so a resolution change costs a camera rebind and nothing else.
     */
    fun setResolution(width: Int, height: Int, framerate: Int) {
        current = current.copy(width = width, height = height, framerate = framerate)
        capturer?.changeCaptureFormat(width, height, framerate)
    }

    fun setLens(front: Boolean) = capturer?.setLensFacing(front)

    val resolution: Triple<Int, Int, Int> get() = Triple(current.width, current.height, current.framerate)

    fun setTorch(on: Boolean) = capturer?.setTorch(on)

    fun setZoom(ratio: Float) = capturer?.setZoom(ratio)

    fun close() {
        if (closed) return
        closed = true
        runCatching { capturer?.dispose() }
        runCatching { textureHelper?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { connection?.close(); connection?.dispose() }
        connection = null
    }

    private val observer = object : PeerConnectionObserverAdapter() {
        override fun onIceCandidate(candidate: IceCandidate) {
            transport.sendCandidate(
                Candidate.newBuilder()
                    .setSdpMid(candidate.sdpMid)
                    .setSdpMLineIndex(candidate.sdpMLineIndex)
                    .setCandidate(candidate.sdp)
                    .build()
            )
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "peer connection $id -> $newState")
            when (newState) {
                PeerConnection.PeerConnectionState.FAILED ->
                    transport.tearDown(Bye.Reason.NO_FRAME, "ice failed")
                PeerConnection.PeerConnectionState.CLOSED ->
                    transport.tearDown(Bye.Reason.HANGUP, "closed")
                else -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "CameraMediaSession"
        const val STREAM_ID = "homecam"
        const val VIDEO_TRACK_ID = "homecam-video"
        const val AUDIO_TRACK_ID = "homecam-audio"
    }
}
