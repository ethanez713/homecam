package com.homecam.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.media.WebRtc
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Away from home the only path between the phones is the tailnet, and there is no STUN or
 * TURN server by design — so media connects only if each side offers its tailnet address
 * as a host candidate. Skipped on a phone with no VPN interface up.
 */
@RunWith(AndroidJUnit4::class)
class TailnetCandidateTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theTailnetAddressIsOfferedAsAHostCandidate() {
        val tailnet = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.toList() }
            .map { it.hostAddress.orEmpty().substringBefore('%') }
            .filter { it.startsWith("100.") }
        assumeTrue("no tailnet interface on this phone", tailnet.isNotEmpty())

        val candidates = CopyOnWriteArrayList<String>()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = WebRtc.factory(context).createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) { candidates += candidate.sdp }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })!!
        try {
            peer.createDataChannel("probe", DataChannel.Init())
            peer.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peer.setLocalDescription(this, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) {}
                override fun onSetFailure(error: String) {}
            }, MediaConstraints())
            Thread.sleep(GATHER_MS)
        } finally {
            peer.dispose()
        }

        candidates.forEach { Log.i(TAG, "candidate $it") }
        assertTrue(
            "tailnet $tailnet missing from ${candidates.map { it.split(' ').getOrNull(4) }}",
            candidates.any { sdp -> tailnet.any { sdp.contains(" $it ") } },
        )
    }

    private companion object {
        const val TAG = "TailnetCandidateTest"
        const val GATHER_MS = 4_000L
    }
}
