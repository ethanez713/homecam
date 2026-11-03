package com.homecam.app.media

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * One live media session, from the camera's side. The camera offers, because it is the
 * side that has something to send.
 *
 * One video track per selected lens, stream id `lens:<id>`. A lens that is selected but
 * not running still has a sender, with no track, so it can start later without a new offer.
 */
class CameraMediaSession(
    private val context: Context,
    private val config: Config,
    private val transport: Transport,
) {
    data class Config(val audio: Boolean = true)

    /** A lens in this session. [capture] is null while that lens is selected but not running. */
    data class LensVideo(val id: String, val capture: ContinuousCapture?)

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
    private val senders = linkedMapOf<String, RtpSender>()
    private val tracks = linkedMapOf<String, VideoTrack>()
    private val captures = linkedMapOf<String, ContinuousCapture>()
    private var shown: Set<String>? = null
    private var primaryLensId = ""
    private val trackSerial = AtomicInteger(0)
    private var audioTrack: AudioTrack? = null
    private var closed = false
    private var lastBytesSent = 0L
    private var lastStatsAtMs = 0L
    private var bitrateCapBps = MAX_BITRATE_BPS

    fun start(lenses: List<LensVideo>, maxBitrateBps: Int = MAX_BITRATE_BPS) {
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

        lenses.forEachIndexed { index, lens -> addLens(lens, primary = index == 0) }
        if (config.audio) addAudio()
        applyBitrateCap(maxBitrateBps)
        createOffer()
    }

    /**
     * The secondary pipeline started or stopped. The sender negotiated at the start of the
     * session gains or loses its track; the offer is not rebuilt.
     */
    fun onCaptureRunning(lensId: String, capture: ContinuousCapture) {
        captures[lensId] = capture
        if (allowed(lensId)) attach(lensId)
    }

    fun onCaptureStopped(lensId: String) {
        captures.remove(lensId)
        senders[lensId]?.setTrack(null, false)
        tracks.remove(lensId)?.let { runCatching { it.dispose() } }
    }

    /** Lenses the viewer has on screen. Any other sender drops its track; recorders are not involved. */
    fun setShown(lensIds: Collection<String>) {
        shown = lensIds.toSet()
        senders.keys.forEach { id ->
            if (allowed(id)) attach(id) else senders[id]?.setTrack(null, false)
        }
    }

    private fun addLens(lens: LensVideo, primary: Boolean) {
        val connection = connection ?: return
        if (primary) primaryLensId = lens.id
        val stream = streamId(lens.id)
        val capture = lens.capture?.takeIf { it.running }
        if (capture != null) captures[lens.id] = capture
        val track = capture?.newTrack(nextTrackId(lens.id))
        // A selected lens that is not running still occupies an m-line, so attaching its
        // track later is not a renegotiation. The primary with no capture adds nothing,
        // which is the single-lens session with no picture.
        val sender = if (track != null) {
            connection.addTrack(track, listOf(stream))
        } else if (!primary) {
            val transceiver = connection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    listOf(stream),
                ),
            )
            transceiver.sender.apply {
                setStreams(listOf(stream))
                setTrack(null, false)
            }
        } else {
            return
        } ?: return
        if (track != null) tracks[lens.id] = track
        senders[lens.id] = sender
        connection.transceivers.firstOrNull { it.sender.id() == sender.id() }
            ?.setCodecPreferences(hardwareH264First())
    }

    private fun allowed(lensId: String): Boolean = shown?.contains(lensId) ?: true

    private fun attach(lensId: String) {
        val sender = senders[lensId] ?: return
        val existing = tracks[lensId]
        if (existing != null) {
            sender.setTrack(existing, false)
            capSender(sender)
            return
        }
        val capture = captures[lensId]?.takeIf { it.running } ?: return
        val track = capture.newTrack(nextTrackId(lensId)) ?: return
        tracks[lensId] = track
        sender.setTrack(track, false)
        capSender(sender)
    }

    private fun nextTrackId(lensId: String) = "homecam-video-$lensId-${trackSerial.incrementAndGet()}"

    /** Lowers or restores the live cap without rebuilding the session. Each video sender gets the same cap. */
    fun setBitrateCap(maxBitrateBps: Int) = applyBitrateCap(maxBitrateBps)

    private fun applyBitrateCap(maxBitrateBps: Int) {
        bitrateCapBps = maxBitrateBps
        val connection = connection ?: return
        if (senders.isEmpty()) return
        senders.values.forEach { capSender(it) }
        val count = senders.size
        val max = maxBitrateBps * count
        val start = (START_BITRATE_BPS * count).coerceAtMost(max)
        val min = (MIN_BITRATE_BPS * count).coerceAtMost(start)
        // libwebrtc starts every call at 300 kbps and ramps over tens of seconds, which on a
        // LAN means the first half-minute of every live view is 360p for no reason.
        connection.setBitrate(min, start, max)
    }

    private fun capSender(sender: RtpSender) {
        sender.parameters = sender.parameters.apply {
            // Detail is what a camera feed is for; a jerkier picture you can read beats a
            // smooth one you cannot.
            degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            // Without a per-encoding cap libwebrtc holds anything above 540p to 2.5 Mbps,
            // whatever setBitrate allows — thin for 1080p.
            encodings.forEach { it.maxBitrateBps = bitrateCapBps }
        }
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
                    if (closed) return
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
        if (closed) return
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
     * are fixed by completely different things. The figures are the primary lens.
     */
    fun stats(onStats: (Stats) -> Unit) {
        val connection = connection ?: return onStats(Stats())
        val sender = senders[primaryLensId]
        if (sender != null) connection.getStats(sender) { deliverStats(it, onStats) }
        else connection.getStats { deliverStats(it, onStats) }
    }

    /** Bytes the named lens's sender has handed to the transport. Stays put while that sender has no track. */
    fun bytesSent(lensId: String, onBytes: (Long) -> Unit) {
        val connection = connection ?: return onBytes(0)
        val sender = senders[lensId] ?: return onBytes(0)
        connection.getStats(sender) { report ->
            onBytes(report.statsMap.values.sumOf { entry ->
                if (entry.type != "outbound-rtp") 0L else bytesOf(entry.members["bytesSent"])
            })
        }
    }

    private fun deliverStats(report: org.webrtc.RTCStatsReport, onStats: (Stats) -> Unit) {
        var bitrateKbps = 0
        var rttMs = 0
        var packetsLost = 0
        var width = 0
        var height = 0

        report.statsMap.values.forEach { entry ->
            when (entry.type) {
                "outbound-rtp" -> {
                    if (entry.members["kind"] == "audio") return@forEach
                    (entry.members["frameWidth"] as? Long)?.let { width = it.toInt() }
                    (entry.members["frameHeight"] as? Long)?.let { height = it.toInt() }
                    val sent = bytesOf(entry.members["bytesSent"])
                    val now = System.currentTimeMillis()
                    val elapsed = (now - lastStatsAtMs).coerceAtLeast(1)
                    if (lastStatsAtMs > 0) bitrateKbps = ((sent - lastBytesSent) * 8 / elapsed).toInt()
                    lastBytesSent = sent
                    lastStatsAtMs = now
                }
                "remote-inbound-rtp" -> {
                    (entry.members["roundTripTime"] as? Double)?.let { rttMs = (it * 1000).toInt() }
                    (entry.members["packetsLost"] as? Int)?.let { packetsLost = it }
                }
            }
        }
        onStats(Stats(bitrateKbps, rttMs, packetsLost, width, height))
    }

    data class Stats(
        val bitrateKbps: Int = 0,
        val rttMs: Int = 0,
        val packetsLost: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
    )

    fun onCandidate(candidate: Candidate) {
        Log.v(TAG, "ice remote ${candidate.candidate}")
        connection?.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    fun close() {
        if (closed) return
        closed = true
        val closing = connection
        val video = tracks.values.toList()
        val audio = audioTrack
        connection = null
        tracks.clear()
        senders.clear()
        captures.clear()
        runCatching { closing?.close() }
        // Offer and description callbacks already queued on WebRTC's signaling thread still hold
        // this connection; freeing it under them is a SIGSEGV in libjingle when a viewer drops
        // within milliseconds of connecting. Closed is safe to call into, disposed is not.
        Handler(Looper.getMainLooper()).postDelayed({
            video.forEach { runCatching { it.dispose() } }
            runCatching { audio?.dispose() }
            runCatching { closing?.dispose() }
        }, DISPOSE_DELAY_MS)
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

    companion object {
        private const val TAG = "CameraMediaSession"
        private const val STREAM_ID = "homecam"
        const val LENS_STREAM_PREFIX = "lens:"
        private const val DISPOSE_DELAY_MS = 2_000L
        private const val AUDIO_TRACK_ID = "homecam-audio"
        private const val MIN_BITRATE_BPS = 300_000
        private const val START_BITRATE_BPS = 2_500_000
        const val MAX_BITRATE_BPS = 8_000_000
        const val THROTTLED_MAX_BITRATE_BPS = MAX_BITRATE_BPS / 2

        fun streamId(lensId: String) = "$LENS_STREAM_PREFIX$lensId"

        fun lensIdOf(streamId: String): String =
            if (streamId.startsWith(LENS_STREAM_PREFIX)) streamId.removePrefix(LENS_STREAM_PREFIX) else ""

        private fun bytesOf(value: Any?): Long = when (value) {
            is java.math.BigInteger -> value.toLong()
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            else -> 0L
        }
    }
}
