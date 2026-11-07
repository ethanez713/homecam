package com.homecam.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CameraService
import com.homecam.app.media.CameraMediaSession
import com.homecam.app.media.ClipRecorder
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.WebRtc
import com.homecam.proto.Bye
import com.homecam.proto.Candidate
import com.homecam.proto.Sdp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A viewer can watch at a lower resolution than the camera records. The live sender
 * scales down; the capture format and the recorder's configured bitrate stay put.
 */
@RunWith(AndroidJUnit4::class)
class LiveResolutionRecordingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun loweringLiveResolutionLeavesTheRecorderAtItsConfiguredBitrate() {
        val wasRunning = CameraService.isRunning.value
        var owner: LifecycleOwner? = null
        var capture: ContinuousCapture? = null
        var recorder: ClipRecorder? = null
        var media: CameraMediaSession? = null
        var dir: File? = null
        try {
            if (wasRunning) releaseCameraService()
            val registry = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
            owner = registry
            instrumentation.runOnMainSync { registry.lifecycle.currentState = Lifecycle.State.STARTED }
            WebRtc.factory(context)
            val format = ContinuousCapture.Format(1920, 1080, 30)
            val opened = ContinuousCapture(context, registry)
            capture = opened
            opened.start(format)
            val clipDir = File(context.cacheDir, "live-resolution-${System.nanoTime()}").apply { mkdirs() }
            dir = clipDir
            val rec = ClipRecorder(clipDir).also { it.start(WebRtc.eglBase.eglBaseContext) }
            recorder = rec
            opened.addSink(rec)
            val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
            while (!rec.recording && System.currentTimeMillis() < deadline) {
                rec.beginClip("live", System.currentTimeMillis())
                Thread.sleep(200)
            }
            assertTrue("clip never opened", rec.recording)

            val session = CameraMediaSession(
                context = context,
                config = CameraMediaSession.Config(audio = false),
                transport = object : CameraMediaSession.Transport {
                    override fun sendOffer(sdp: Sdp) = Unit
                    override fun sendCandidate(candidate: Candidate) = Unit
                    override fun tearDown(reason: Bye.Reason, detail: String) = Unit
                },
            )
            media = session
            session.start(listOf(CameraMediaSession.LensVideo("back-wide", opened)))
            val bitrate = rec.config.bitrate
            val width = rec.config.width
            val height = rec.config.height
            val fps = rec.config.framerate
            session.setLiveResolution(1280, 720)
            Thread.sleep(SETTLE_MS)

            assertEquals(format, opened.format)
            assertEquals(bitrate, rec.config.bitrate)
            assertEquals(width, rec.config.width)
            assertEquals(height, rec.config.height)
            assertEquals(fps, rec.config.framerate)
            assertEquals(6_000_000, rec.config.bitrate)
            assertTrue("lowering the live picture closed the clip", rec.recording)
            assertEquals(1.5, session.scaleResolutionDownBy, 0.001)
        } finally {
            runCatching { media?.close() }
            capture?.let { live -> recorder?.let { live.removeSink(it) } }
            runCatching { recorder?.stop() }
            runCatching { capture?.stop() }
            owner?.let { living ->
                instrumentation.runOnMainSync {
                    (living.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
                }
            }
            dir?.deleteRecursively()
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

    private companion object {
        const val OPEN_TIMEOUT_MS = 8_000L
        const val SETTLE_MS = 300L
    }
}
