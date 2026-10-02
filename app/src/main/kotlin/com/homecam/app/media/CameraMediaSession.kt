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
import org.webrtc.RtpCapabilities
import org.webrtc.RtpParameters
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID

/**
 * One live media session, from the camera's side. The camera offers, because it is the
 * side that has something to send.
 */
class CameraMediaSession(
    private val context: Context,
    private val capture: ContinuousCapture,
    private val config: Config,
    private val transport: Transport,
) {
    data class Config(val audio: Boolean = true)

    interface Transport {
        fun sendOffer(sdp: Sdp)
        fun sendCandidate(candidate: Candidate)
        /** Called when the session must end, with the reason to show and to log. */
        fun tearDown(reason: Bye.Reason, detail: String)
    }

    val id: String = UUID.randomUUID().toString()

    private val factory = WebRtc.factory(context)
    private val commitment = DtlsCommitment()

    private var connection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var closed = false
    private var lastBytesSent = 0L
    private var lastStatsAtMs = 0L

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

    /**
     * The track draws from the always-on capture rather than starting a camera of its
     * own, so a second viewer costs a track and not a second camera bind.
     */
    private fun addVideo() {
        val connection = connection ?: return
        val track = capture.newTrack() ?: return
        videoTrack = track
        val sender = connection.addTrack(track, listOf(STREAM_ID))
        connection.transceivers.firstOrNull { it.sender.id() == sender.id() }
            ?.setCodecPreferences(hardwareH264First())
        sender.parameters = sender.parameters.apply {
            // Detail is what a camera feed is for; a jerkier picture you can read beats a
            // smooth one you cannot.
            degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            // Without a per-encoding cap libwebrtc holds anything above 540p to 2.5 Mbps,
            // whatever setBitrate allows — thin for 1080p.
            encodings.forEach { it.maxBitrateBps = MAX_BITRATE_BPS }
        }
        // libwebrtc starts every call at 300 kbps and ramps over tens of seconds, which on a
        // LAN means the first half-minute of every live view is 360p for no reason.
        connection.setBitrate(MIN_BITRATE_BPS, START_BITRATE_BPS, MAX_BITRATE_BPS)
    }

    /** Without this the offer leads with VP8, which the hardware encodes far worse (D6). */
    private fun hardwareH264First(): List<RtpCapabilities.CodecCapability> =
        factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            .sortedBy { if (it.name.equals("H264", ignoreCase = true)) 0 else 1 }
            .also { codecs -> Log.i(TAG, "video codec preference: ${codecs.joinToString { it.name }}") }

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

    /**
     * Live transport numbers, straight from libwebrtc. Worth surfacing because "the video
     * is choppy" and "the camera is thermally throttled" look identical from the sofa and
     * are fixed by completely different things.
     */
    fun stats(onStats: (Stats) -> Unit) {
        val connection = connection ?: return onStats(Stats())
        connection.getStats { report ->
            var bitrateKbps = 0
            var rttMs = 0
            var packetsLost = 0
            var width = 0
            var height = 0

            report.statsMap.values.forEach { entry ->
                when (entry.type) {
                    "outbound-rtp" -> {
                        (entry.members["frameWidth"] as? Long)?.let { width = it.toInt() }
                        (entry.members["frameHeight"] as? Long)?.let { height = it.toInt() }
                        (entry.members["bytesSent"] as? java.math.BigInteger)?.let {
                            val now = System.currentTimeMillis()
                            val elapsed = (now - lastStatsAtMs).coerceAtLeast(1)
                            if (lastStatsAtMs > 0) {
                                bitrateKbps = ((it.toLong() - lastBytesSent) * 8 / elapsed).toInt()
                            }
                            lastBytesSent = it.toLong()
                            lastStatsAtMs = now
                        }
                    }
                    "remote-inbound-rtp" -> {
                        (entry.members["roundTripTime"] as? Double)?.let { rttMs = (it * 1000).toInt() }
                        (entry.members["packetsLost"] as? Int)?.let { packetsLost = it }
                    }
                }
            }
            onStats(Stats(bitrateKbps, rttMs, packetsLost, width, height))
        }
    }

    data class Stats(
        val bitrateKbps: Int = 0,
        val rttMs: Int = 0,
        val packetsLost: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
    )

    fun onCandidate(candidate: Candidate) {
        Log.i(TAG, "ice remote ${candidate.candidate}")
        connection?.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { videoTrack?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { connection?.close(); connection?.dispose() }
        connection = null
    }

    private val observer = object : PeerConnectionObserverAdapter() {
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.i(TAG, "ice local ${candidate.sdp}")
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
        const val AUDIO_TRACK_ID = "homecam-audio"
        const val MIN_BITRATE_BPS = 300_000
        const val START_BITRATE_BPS = 2_500_000
        const val MAX_BITRATE_BPS = 8_000_000
    }
}
