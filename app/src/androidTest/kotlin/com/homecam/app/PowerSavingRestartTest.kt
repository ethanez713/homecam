package com.homecam.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.media.CameraXVideoCapturer
import com.homecam.app.media.ContinuousCapture
import com.homecam.core.diagnostics.PowerSavingReason
import com.homecam.core.media.PowerSaving
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

/**
 * A capture restart builds a new capturer, which starts at the full rate. Power saving
 * stays engaged across that restart and does not ask again, so the idle rate has to be
 * put back by capture itself. Neither capturer is started, so this does not open a camera.
 */
@RunWith(AndroidJUnit4::class)
class PowerSavingRestartTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aRestartWhileEngagedLeavesTheNewCapturerAtTheIdleRate() {
        val fixture = Fixture(context)
        val restarted = fixture.restart()

        assertEquals(CameraXVideoCapturer.IDLE_FPS, restarted.targetFps)
        assertTrue(fixture.saving.isEngaged)
        assertEquals(listOf(true to PowerSavingReason.IDLE), fixture.log)
    }

    @Test
    fun aRestartAfterReleaseLeavesTheNewCapturerAtTheFullRate() {
        val fixture = Fixture(context)
        fixture.saving.release(PowerSavingReason.VIEWER)
        val restarted = fixture.restart()

        assertEquals(fixture.capture.format.framerate, restarted.targetFps)
        assertEquals(
            listOf(true to PowerSavingReason.IDLE, false to PowerSavingReason.VIEWER),
            fixture.log,
        )
    }

    private class Fixture(context: android.content.Context) {
        val capture = ScriptedCapture(context, UnusedLifecycle())
        val log = mutableListOf<Pair<Boolean, String>>()
        var now = 0L
        val saving = PowerSaving(
            nowMs = { now },
            setSaving = capture::setPowerSaving,
            log = { engaged, reason -> log += engaged to reason },
        )

        init {
            capture.start()
            now = PowerSaving.QUIET_MS
            saving.tick(sceneIdle = true)
            check(capture.opened.last().targetFps == CameraXVideoCapturer.IDLE_FPS)
            check(log == listOf(true to PowerSavingReason.IDLE))
        }

        fun restart(): CameraXVideoCapturer {
            capture.stop()
            capture.start()
            return capture.opened.last()
        }
    }

    private class ScriptedCapture(context: android.content.Context, owner: LifecycleOwner) : ContinuousCapture(context, owner) {
        val opened = mutableListOf<CameraXVideoCapturer>()

        override fun createCapturer(): CameraXVideoCapturer = super.createCapturer().also { opened += it }

        override fun attachCapturer(
            videoCapturer: CameraXVideoCapturer,
            format: Format,
        ): Pair<SurfaceTextureHelper?, VideoSource?> = null to null
    }

    private class UnusedLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
