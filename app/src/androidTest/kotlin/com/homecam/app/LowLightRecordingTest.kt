package com.homecam.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CameraService
import com.homecam.app.camera.LensSource
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.WebRtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Low light adds one stop of exposure compensation on a lens that supports it,
 * and turning it off puts the index back.
 */
@RunWith(AndroidJUnit4::class)
class LowLightRecordingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun lowLightChangesTheExposureCompensationAndOffRestoresIt() {
        assumeTrue("the primary lens has no exposure compensation", primarySupportsCompensation())
        val wasRunning = CameraService.isRunning.value
        var owner: LifecycleOwner? = null
        var capture: ContinuousCapture? = null
        try {
            if (wasRunning) releaseCameraService()
            val registry = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
            owner = registry
            instrumentation.runOnMainSync { registry.lifecycle.currentState = Lifecycle.State.STARTED }
            WebRtc.factory(context)
            val opened = ContinuousCapture(context, registry)
            capture = opened
            opened.start()
            val oneStop = await("exposure compensation never became readable") { opened.oneStopCompensationIndex() }
            assumeTrue("one stop clamps to the index already in use", oneStop != 0)
            opened.setLowLight(true)
            assertEquals(oneStop, await("low light did not change the exposure index") { opened.exposureCompensationIndex()?.takeIf { it == oneStop } })
            opened.setLowLight(false)
            assertEquals(0, await("turning low light off did not restore the exposure index") { opened.exposureCompensationIndex()?.takeIf { it == 0 } })
        } finally {
            runCatching { capture?.stop() }
            owner?.let { living ->
                instrumentation.runOnMainSync {
                    (living.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
                }
            }
            if (wasRunning) CameraService.start(context)
        }
    }

    private fun primarySupportsCompensation(): Boolean {
        val primary = LensSource.lenses(context).firstOrNull { !it.front } ?: return false
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val range = runCatching { manager.getCameraCharacteristics(primary.cameraId) }.getOrNull()
            ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: return false
        return range.lower != 0 || range.upper != 0
    }

    private fun <T> await(message: String, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(50)
        }
        assertTrue(message, false)
        error(message)
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
    }
}
