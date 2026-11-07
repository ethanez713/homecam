package com.homecam.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CameraService
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.WebRtc
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The zoom-in lock is re-applied when capture opens again. With it off, a restart
 * still begins at the lens minimum.
 */
@RunWith(AndroidJUnit4::class)
class ZoomLockRecordingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun aLockedZoomSurvivesACaptureRestart() {
        assumeTrue("the back camera does not zoom", backCameraZooms())
        val wasRunning = CameraService.isRunning.value
        var owner: LifecycleOwner? = null
        var capture: ContinuousCapture? = null
        try {
            if (wasRunning) releaseCameraService()
            val opened = startCapture().also { (living, live) ->
                owner = living
                capture = live
            }.second
            opened.setZoomLock(true, LOCKED)
            opened.setZoom(LOCKED)
            awaitZoom(opened, LOCKED)
            opened.stop()
            opened.start()
            awaitZoom(opened, LOCKED)
        } finally {
            release(capture, owner, wasRunning)
        }
    }

    @Test
    fun anUnlockedRestartOpensAtTheLensMinimum() {
        assumeTrue("the back camera does not zoom", backCameraZooms())
        val wasRunning = CameraService.isRunning.value
        var owner: LifecycleOwner? = null
        var capture: ContinuousCapture? = null
        try {
            if (wasRunning) releaseCameraService()
            val opened = startCapture().also { (living, live) ->
                owner = living
                capture = live
            }.second
            opened.setZoom(LOCKED)
            awaitZoom(opened, LOCKED)
            opened.stop()
            opened.start()
            awaitZoom(opened, 0f, tolerance = 0.08f)
        } finally {
            release(capture, owner, wasRunning)
        }
    }

    private fun startCapture(): Pair<LifecycleOwner, ContinuousCapture> {
        val registry = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        instrumentation.runOnMainSync { registry.lifecycle.currentState = Lifecycle.State.STARTED }
        WebRtc.factory(context)
        val opened = ContinuousCapture(context, registry)
        opened.start()
        return registry to opened
    }

    private fun awaitZoom(capture: ContinuousCapture, target: Float, tolerance: Float = 0.05f) {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        var latest: Float? = null
        while (System.currentTimeMillis() < deadline) {
            latest = capture.linearZoom()
            if (latest != null && abs(latest - target) <= tolerance) return
            Thread.sleep(50)
        }
        assertEquals(target.toDouble(), (latest ?: Float.NaN).toDouble(), tolerance.toDouble())
    }

    private fun backCameraZooms(): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.any { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                return@any false
            }
            val ratio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
            } else {
                1f
            }
            val digital = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            maxOf(ratio, digital) > 1.2f
        }
    }

    private fun release(capture: ContinuousCapture?, owner: LifecycleOwner?, wasRunning: Boolean) {
        runCatching { capture?.stop() }
        owner?.let { living ->
            instrumentation.runOnMainSync {
                (living.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
            }
        }
        if (wasRunning) CameraService.start(context)
    }

    private fun releaseCameraService() {
        CameraService.stop(context)
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (CameraService.isRunning.value && System.currentTimeMillis() < deadline) Thread.sleep(50)
        instrumentation.runOnMainSync { }
        assertTrue("camera service did not release the cameras", !CameraService.isRunning.value)
    }

    private companion object {
        const val LOCKED = 0.6f
        const val OPEN_TIMEOUT_MS = 8_000L
    }
}
