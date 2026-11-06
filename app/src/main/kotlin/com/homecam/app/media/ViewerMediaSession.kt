package com.homecam.app.media

import android.content.Context
import android.util.Log
import com.homecam.core.signaling.DtlsCommitment
import com.homecam.core.signaling.DtlsFingerprint
import com.homecam.proto.Bye
import com.homecam.proto.Candidate
import com.homecam.proto.Sdp
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.MediaStream
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/** The viewer's side of a live session: it answers, and it renders what arrives. */
class ViewerMediaSession(
    context: Context,
    private val transport: Transport,
) {
    interface Transport {
        fun sendAnswer(sdp: Sdp)
        fun sendCandidate(candidate: Candidate)
        fun onVideoTrack(track: VideoTrack, lensId: String)
        fun tearDown(reason: Bye.Reason, detail: String)
    }

    private val factory = WebRtc.factory(context)
    private val commitment = DtlsCommitment()
    private var connection: PeerConnection? = null
    private var microphone: AudioTrack? = null
    private var closed = false
    private val diagnostics = com.homecam.app.AppGraph.get(context).viewerDiagnostics
    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statsTick = object : Runnable {
        override fun run() {
            if (closed) return
            connection?.getStats { report -> logInboundVideo(report) }
            statsHandler.postDelayed(this, STATS_EVERY_MS)
        }
    }

    /** What arrived, per video track: the viewer's half of telling a stalled camera from a stalled link. */
    private fun logInboundVideo(report: org.webrtc.RTCStatsReport) {
        report.statsMap.values
            .filter { it.type == "inbound-rtp" && it.members["kind"] == "video" }
            .forEach { entry ->
                val m = entry.members
                diagnostics.liveStats(
                    listOf(
                        "track" to (m["trackIdentifier"] ?: m["mid"] ?: "").toString(),
                        "fps" to ((m["framesPerSecond"] as? Double) ?: 0.0),
                        "frames_received" to (m["framesReceived"] ?: 0L).toString().toLong(),
                        "frames_decoded" to (m["framesDecoded"] ?: 0L).toString().toLong(),
                        "frames_dropped" to (m["framesDropped"] ?: 0L).toString().toLong(),
                        "freezes" to (m["freezeCount"] ?: 0L).toString().toLong(),
                        "freeze_s" to ((m["totalFreezesDuration"] as? Double) ?: 0.0),
                        "pli" to (m["pliCount"] ?: 0L).toString().toLong(),
                        "nack" to (m["nackCount"] ?: 0L).toString().toLong(),
                        "packets_lost" to (m["packetsLost"] ?: 0L).toString().toLong(),
                        "width" to (m["frameWidth"] ?: 0L).toString().toLong(),
                        "height" to (m["frameHeight"] ?: 0L).toString().toLong(),
                    ),
                )
            }
    }

    /**
     * Push-to-talk. The track is added when the session is answered and starts disabled,
     * so talking is a mute toggle rather than a renegotiation — adding a sender
     * mid-session would mean a fresh offer/answer round trip every time you press the
     * button, and a new SDP to fingerprint-check for no reason.
     */
    fun setMicrophoneEnabled(enabled: Boolean) { microphone?.setEnabled(enabled) }

    fun onOffer(offer: Sdp) {
        when (val result = commitment.check(offer.sdp, offer.dtlsFingerprint)) {
            DtlsCommitment.Result.ACCEPTED -> accept(offer)
            // §2.5: a session whose media path does not match the identity that was
            // authenticated is torn down rather than downgraded, and it is logged.
            else -> transport.tearDown(Bye.Reason.FINGERPRINT_MISMATCH, result.name)
        }
    }

    private fun accept(offer: Sdp) {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = connection ?: factory.createPeerConnection(rtcConfig, observer)?.also {
            connection = it
        } ?: run {
            transport.tearDown(Bye.Reason.INCOMPATIBLE, "could not create a peer connection")
            return
        }

        if (microphone == null) {
            val source = factory.createAudioSource(MediaConstraints())
            microphone = factory.createAudioTrack(MIC_TRACK_ID, source).apply {
                setEnabled(false)
                peer.addTrack(this, listOf(STREAM_ID))
            }
        }

        peer.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() = answer(peer)
                override fun onSetFailure(error: String) =
                    transport.tearDown(Bye.Reason.INCOMPATIBLE, "remote description: $error")
            },
            SessionDescription(SessionDescription.Type.OFFER, offer.sdp),
        )
    }

    private fun answer(peer: PeerConnection) {
        peer.createAnswer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    peer.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                val fingerprint = DtlsFingerprint.parse(description.description)
                                    ?: return transport.tearDown(
                                        Bye.Reason.INCOMPATIBLE,
                                        "local sdp carries no DTLS fingerprint",
                                    )
                                transport.sendAnswer(
                                    Sdp.newBuilder()
                                        .setSdp(description.description)
                                        .setDtlsFingerprint(fingerprint)
                                        .build()
                                )
                            }

                            override fun onSetFailure(error: String) =
                                transport.tearDown(Bye.Reason.INCOMPATIBLE, "local description: $error")
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String) =
                    transport.tearDown(Bye.Reason.INCOMPATIBLE, "answer: $error")
            },
            MediaConstraints(),
        )
    }

    fun onCandidate(candidate: Candidate) {
        Log.v(TAG, "ice remote ${candidate.candidate}")
        connection?.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    fun close() {
        if (closed) return
        closed = true
        statsHandler.removeCallbacks(statsTick)
        runCatching { microphone?.dispose() }
        microphone = null
        runCatching { connection?.close(); connection?.dispose() }
        connection = null
    }

    private val observer = object : PeerConnectionObserverAdapter() {
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.v(TAG, "ice local ${candidate.sdp}")
            transport.sendCandidate(
                Candidate.newBuilder()
                    .setSdpMid(candidate.sdpMid)
                    .setSdpMLineIndex(candidate.sdpMLineIndex)
                    .setCandidate(candidate.sdp)
                    .build()
            )
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val track = receiver.track()
            if (track is VideoTrack && track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                val lensId = streams.firstNotNullOfOrNull { stream ->
                    CameraMediaSession.lensIdOf(stream.id).takeIf { it.isNotEmpty() }
                }.orEmpty()
                transport.onVideoTrack(track, lensId)
            }
        }

        override fun onAddStream(stream: MediaStream) {
            val lensId = CameraMediaSession.lensIdOf(stream.id)
            stream.videoTracks.forEach { track -> transport.onVideoTrack(track, lensId) }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "peer connection -> $newState")
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                statsHandler.removeCallbacks(statsTick)
                statsHandler.postDelayed(statsTick, STATS_EVERY_MS)
            }
            if (newState == PeerConnection.PeerConnectionState.FAILED) {
                transport.tearDown(Bye.Reason.NO_FRAME, "ice failed")
            }
        }
    }

    private companion object {
        const val TAG = "ViewerMediaSession"
        const val STREAM_ID = "homecam-viewer"
        const val MIC_TRACK_ID = "homecam-viewer-audio"
        const val STATS_EVERY_MS = 3_000L
    }
}
