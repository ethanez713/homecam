package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CameraService
import com.homecam.core.rpc.RpcException
import com.homecam.proto.RecordingMode
import com.homecam.proto.SessionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Privacy mode has to release the camera, refuse a new live session, and keep continuous
 * recording from opening another clip. Turning it off has to deliver frames again.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyModeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun privacyOnStopsCaptureAndRefusesASessionThenFramesReturn() {
        val graph = AppGraph.get(context)
        val previousPrivacy = graph.settings.current.privacyMode
        val previousMode = graph.settings.current.recordingMode
        val wasRunning = CameraService.isRunning.value
        try {
            if (wasRunning) releaseCameraService()
            runBlocking {
                graph.settings.update {
                    setPrivacyMode(false)
                    setRecordingMode(RecordingMode.CONTINUOUS)
                }
            }
            CameraService.start(context)
            val service = awaitCapturing()

            runBlocking { graph.settings.update { setPrivacyMode(true) } }
            awaitStopped(service)

            val refused = runCatching {
                runBlocking { service.startSession("privacy-test", SessionConfig.getDefaultInstance()) }
            }.exceptionOrNull()
            assertTrue("expected CAMERA_DISABLED, was $refused", refused is RpcException)
            val message = refused?.message.orEmpty()
            assertTrue(message, message.contains("CAMERA_DISABLED") && message.contains("privacy mode"))

            val settled = awaitStableClipCount()
            val quietUntil = System.currentTimeMillis() + QUIET_MS
            while (System.currentTimeMillis() < quietUntil) {
                assertFalse("capture restarted during privacy mode", service.captureRunning())
                assertEquals(settled, clipCount())
                Thread.sleep(200)
            }

            val before = service.deliveredFrames()
            runBlocking { graph.settings.update { setPrivacyMode(false) } }
            awaitFramesSince(service, before)
        } finally {
            runBlocking {
                graph.settings.update {
                    setPrivacyMode(previousPrivacy)
                    setRecordingMode(previousMode)
                }
            }
            if (wasRunning) {
                if (!CameraService.isRunning.value) CameraService.start(context)
            } else {
                releaseCameraService()
            }
        }
    }

    private fun awaitCapturing(): CameraService {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        var service = CameraService.runningInstance
        while (System.currentTimeMillis() < deadline) {
            service = CameraService.runningInstance
            if (service != null && service.captureRunning() && service.deliveredFrames() > 0L) return service
            Thread.sleep(50)
        }
        error(
            "capture did not start (running=${service?.captureRunning()} frames=${service?.deliveredFrames()})",
        )
    }

    private fun awaitStopped(service: CameraService) {
        val deadline = System.currentTimeMillis() + TRANSITION_MS
        while (service.captureRunning() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse("capture still running after privacy mode", service.captureRunning())
    }

    private fun awaitFramesSince(service: CameraService, before: Long) {
        val deadline = System.currentTimeMillis() + TRANSITION_MS
        while (service.deliveredFrames() <= before && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(
            "no frames after privacy mode turned off (before=$before now=${service.deliveredFrames()} running=${service.captureRunning()})",
            service.deliveredFrames() > before,
        )
    }

    /** The clip closed by privacy mode is indexed asynchronously. Wait until that settles. */
    private fun awaitStableClipCount(): Int {
        val deadline = System.currentTimeMillis() + SETTLE_MS
        var last = clipCount()
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            val now = clipCount()
            if (now != last) {
                last = now
                stableSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - stableSince >= STABLE_MS) {
                return last
            }
        }
        return last
    }

    private fun clipCount(): Int = runBlocking { AppGraph.get(context).database.clips().all().size }

    private fun releaseCameraService() {
        CameraService.stop(context)
        val deadline = System.currentTimeMillis() + 8_000
        while (CameraService.isRunning.value && System.currentTimeMillis() < deadline) Thread.sleep(50)
        instrumentation.runOnMainSync { }
        assertTrue("camera service did not stop", !CameraService.isRunning.value)
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 20_000L
        const val TRANSITION_MS = 5_000L
        const val QUIET_MS = 5_000L
        const val SETTLE_MS = 3_000L
        const val STABLE_MS = 1_000L
    }
}
