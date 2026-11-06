package com.homecam.app

import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CameraService
import com.homecam.app.camera.ConcurrentPairs
import com.homecam.app.camera.LensSource
import com.homecam.app.camera.Recorder
import com.homecam.app.data.ClipStore
import com.homecam.app.data.HomecamDatabase
import com.homecam.app.media.CameraBinder
import com.homecam.app.media.CameraMediaSession
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.PeerConnectionObserverAdapter
import com.homecam.app.media.SdpObserverAdapter
import com.homecam.app.media.WebRtc
import com.homecam.core.lens.LensCatalog
import com.homecam.core.lens.LensEntry
import com.homecam.core.signaling.DtlsFingerprint
import com.homecam.proto.Bye
import com.homecam.proto.Candidate
import com.homecam.proto.RecordingMode
import com.homecam.proto.Sdp
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

/**
 * A primary lens switch during a live view rebinds the camera under a running WebRTC track.
 * The live video has to keep flowing on the same session, and the main thread has to stay free.
 */
@RunWith(AndroidJUnit4::class)
class LiveLensSwitchTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun liveVideoKeepsFlowingAcrossAPrimaryLensSwitch() {
        val wide = LensSource.lens(context, LensCatalog.BACK_WIDE)
        val ultra = LensSource.lens(context, LensCatalog.BACK_ULTRAWIDE)
        assumeTrue("device lacks a wide and an ultra-wide", wide != null && ultra != null)
        wide!!
        val wasRunning = CameraService.isRunning.value
        var owner: LifecycleOwner? = null
        var capture: ContinuousCapture? = null
        var media: CameraMediaSession? = null
        var answerer: PeerConnection? = null
        try {
            if (wasRunning) releaseCameraService()
            val registry = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
            owner = registry
            instrumentation.runOnMainSync { registry.lifecycle.currentState = Lifecycle.State.STARTED }
            WebRtc.factory(context)
            val binder = CameraBinder(context, registry)
            val primary = ContinuousCapture(context, registry, binder)
            capture = primary
            // A rotated camera rewraps every frame before the encoder, so test that path.
            primary.setOrientation(270)
            primary.start(format = ContinuousCapture.Format(1920, 1080, 30), lens = wide)
            awaitFrames(primary, wide.id)

            val fromCamera = mutableListOf<Candidate>()
            val fromAnswerer = mutableListOf<IceCandidate>()
            val iceLock = Any()
            var remoteReady = false
            val offer = AtomicReference<Sdp?>(null)
            val offerLatch = CountDownLatch(1)
            val teardown = AtomicReference<String?>(null)
            val session = CameraMediaSession(
                context = context,
                config = CameraMediaSession.Config(audio = false),
                transport = object : CameraMediaSession.Transport {
                    override fun sendOffer(sdp: Sdp) {
                        offer.set(sdp)
                        offerLatch.countDown()
                    }

                    override fun sendCandidate(candidate: Candidate) {
                        synchronized(iceLock) {
                            val peer = answerer
                            if (peer != null && remoteReady) peer.addIceCandidate(iceOf(candidate))
                            else fromCamera += candidate
                        }
                    }

                    override fun tearDown(reason: Bye.Reason, detail: String) {
                        teardown.set("$reason $detail")
                    }
                },
            )
            media = session
            // The secondary starts paused, as when health holds it off: its sender is negotiated
            // with no track and gains one once the capture runs, without a new offer.
            session.start(listOf(CameraMediaSession.LensVideo(wide.id, primary)))
            assertTrue("camera produced no offer", offerLatch.await(OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val offerSdp = offer.get() ?: error("missing offer")

            val peer = WebRtc.factory(context).createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                },
                object : PeerConnectionObserverAdapter() {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        synchronized(iceLock) { fromAnswerer += candidate }
                        session.onCandidate(candidateOf(candidate))
                    }
                },
            ) ?: error("could not create the answerer")
            answerer = peer
            setDescription(peer, SessionDescription.Type.OFFER, offerSdp.sdp, remote = true)
            synchronized(iceLock) {
                remoteReady = true
                fromCamera.forEach { peer.addIceCandidate(iceOf(it)) }
                fromCamera.clear()
            }
            val answer = createAnswer(peer)
            val fingerprint = DtlsFingerprint.parse(answer.description)
                ?: error("answer carries no DTLS fingerprint")
            session.onAnswer(
                Sdp.newBuilder().setSdp(answer.description).setDtlsFingerprint(fingerprint).build(),
            )
            synchronized(iceLock) {
                fromAnswerer.forEach { session.onCandidate(candidateOf(it)) }
            }
            val first = awaitBytes(session, wide.id) {
                synchronized(iceLock) { fromAnswerer.forEach { session.onCandidate(candidateOf(it)) } }
            }
            assertTrue("no live video before the switch (${teardown.get()})", first > 0)
            // Rapid switching through every lens soon after the session opens, while the bandwidth
            // estimate is still ramping, is the hardest case for the encoder to recover from.
            val front = LensSource.lens(context, LensCatalog.FRONT)
            val sequence = listOfNotNull(ultra, wide, ultra, front, ultra, wide, ultra, front)
            Thread.sleep(1_500)
            for (lens in sequence) {
                primary.setLens(lens!!)
                Thread.sleep(1_200)
            }
            val perSecond = (1..12).map {
                Thread.sleep(1_000)
                statsOf(session)
            }
            android.util.Log.i("LiveLensSwitchTest", "after the last switch: " +
                perSecond.joinToString { "%.0ffps/%s".format(it.framesPerSecond, it.qualityLimitation) } + " teardown=${teardown.get()}")
            val mainFree = CountDownLatch(1)
            instrumentation.runOnMainSync { mainFree.countDown() }
            assertTrue("main thread blocked after the switches", mainFree.await(1, TimeUnit.SECONDS))
            assertTrue(
                "live video did not recover to 15 fps within 12 s of the last switch: " +
                    perSecond.joinToString { "%.0f/%s".format(it.framesPerSecond, it.qualityLimitation) },
                perSecond.takeLast(4).all { it.framesPerSecond >= 15 },
            )
        } finally {
            runCatching { media?.close() }
            runCatching { answerer?.dispose() }
            runCatching { capture?.stop() }
            owner?.let { living ->
                instrumentation.runOnMainSync {
                    (living.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
                }
            }
            if (wasRunning) CameraService.start(context)
        }
    }

    private fun releaseCameraService() {
        CameraService.stop(context)
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (CameraService.isRunning.value && System.currentTimeMillis() < deadline) Thread.sleep(50)
        instrumentation.runOnMainSync { }
        assertTrue("camera service did not release the cameras", !CameraService.isRunning.value)
    }

    private fun awaitFrames(capture: ContinuousCapture, what: String) {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (capture.frameCount < FRAMES && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(
            "$what delivered no frames (camera error=${capture.lastCameraErrorCode})",
            capture.frameCount >= FRAMES,
        )
    }

    private fun awaitRecording(recorder: Recorder) {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (!recorder.recording && System.currentTimeMillis() < deadline) {
            recorder.tick()
            Thread.sleep(200)
        }
        assertTrue("secondary did not start recording", recorder.recording)
    }

    private fun awaitBytes(media: CameraMediaSession, lensId: String, onWait: () -> Unit = {}): Long {
        val deadline = System.currentTimeMillis() + BYTES_TIMEOUT_MS
        var last = 0L
        while (System.currentTimeMillis() < deadline) {
            onWait()
            last = bytesSent(media, lensId)
            if (last > 0) return last
            Thread.sleep(200)
        }
        return last
    }

    private fun bytesSent(media: CameraMediaSession, lensId: String): Long {
        val latch = CountDownLatch(1)
        val value = AtomicLong(0)
        media.bytesSent(lensId) {
            value.set(it)
            latch.countDown()
        }
        assertTrue("stats for $lensId timed out", latch.await(STATS_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return value.get()
    }

    private fun setDescription(peer: PeerConnection, type: SessionDescription.Type, sdp: String, remote: Boolean) {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val observer = object : SdpObserverAdapter() {
            override fun onSetSuccess() { latch.countDown() }
            override fun onSetFailure(error: String) {
                failure.set(error)
                latch.countDown()
            }
        }
        val description = SessionDescription(type, sdp)
        if (remote) peer.setRemoteDescription(observer, description) else peer.setLocalDescription(observer, description)
        assertTrue("set $type timed out", latch.await(OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        failure.get()?.let { error("set $type: $it") }
    }

    private fun createAnswer(peer: PeerConnection): SessionDescription {
        val created = CountDownLatch(1)
        val holder = AtomicReference<SessionDescription?>(null)
        val failure = AtomicReference<String?>(null)
        peer.createAnswer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    holder.set(description)
                    created.countDown()
                }

                override fun onCreateFailure(error: String) {
                    failure.set(error)
                    created.countDown()
                }
            },
            MediaConstraints(),
        )
        assertTrue("answer timed out", created.await(OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        failure.get()?.let { error("answer: $it") }
        val description = holder.get() ?: error("answer missing")
        setDescription(peer, SessionDescription.Type.ANSWER, description.description, remote = false)
        return description
    }

    private fun iceOf(candidate: Candidate) =
        IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)

    private fun candidateOf(candidate: IceCandidate): Candidate = Candidate.newBuilder()
        .setSdpMid(candidate.sdpMid)
        .setSdpMLineIndex(candidate.sdpMLineIndex)
        .setCandidate(candidate.sdp)
        .build()

    private fun statsOf(media: CameraMediaSession): CameraMediaSession.Stats {
        val latch = CountDownLatch(1)
        val value = java.util.concurrent.atomic.AtomicReference(CameraMediaSession.Stats())
        media.stats {
            value.set(it)
            latch.countDown()
        }
        assertTrue("stats timed out", latch.await(STATS_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return value.get()
    }

    private fun dirBytes(dir: File) = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val FRAMES = 5
        const val OPEN_TIMEOUT_MS = 8_000L
        const val OFFER_TIMEOUT_MS = 8_000L
        const val BYTES_TIMEOUT_MS = 15_000L
        const val STATS_TIMEOUT_MS = 2_000L
        const val SWITCH_SETTLE_MS = 4_000L
        const val GROW_MS = 4_000L
    }
}
