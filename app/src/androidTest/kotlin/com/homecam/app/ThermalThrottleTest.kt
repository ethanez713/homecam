package com.homecam.app

import android.content.Intent
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.LiveSessionThrottle
import com.homecam.app.camera.ScreenDimmer
import com.homecam.app.camera.ThermalThrottle
import com.homecam.app.camera.ThrottleStepListener
import com.homecam.app.debug.ThrottleProbeActivity
import com.homecam.app.detect.DetectionPipeline
import com.homecam.app.media.CameraXVideoCapturer
import com.homecam.app.media.ContinuousCapture
import com.homecam.core.health.AxisState
import com.homecam.core.health.Cap
import com.homecam.core.health.HealthSample
import com.homecam.core.health.HealthState
import com.homecam.core.health.Level
import com.homecam.core.health.ThermalStatus
import com.homecam.proto.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Steps 1 and 3 of the throttle: idle capture rate and the screen, engaged and undone.
 * The capturer is never started, so this does not open a camera.
 */
@RunWith(AndroidJUnit4::class)
class ThermalThrottleTest {

    @Test
    fun throttleLowersTheFrameRateAndDimsTheScreenThenUndoesBoth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, ThrottleProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as ThrottleProbeActivity
        val capturer = CameraXVideoCapturer(context, UnusedLifecycle())
        val pipeline = DetectionPipeline(context, { SettingsSnapshot.getDefaultInstance() }) { _, _ -> }
        val screen = ScreenDimmer()
        val steps = mutableListOf<Pair<String, Boolean>>()
        val throttle = ThermalThrottle(
            setCaptureThrottled = capturer::setThermalThrottle,
            setDetectorThrottled = pipeline::setThrottled,
            live = LiveSessionThrottle(
                session = { null },
                format = { ContinuousCapture.Format(1920, 1080, 30) },
                applyFormat = {},
            ),
            screen = screen,
            listener = ThrottleStepListener { step, engaged, _, _ -> steps += step to engaged },
        )
        try {
            try {
            instrumentation.runOnMainSync {
                screen.track(activity.window)
                val windowBefore = activity.window.attributes.screenBrightness
                val recordingFps = capturer.targetFps
                val idleInterval = pipeline.detectIntervalMs
                assertEquals(30, recordingFps)
                assertEquals(15, CameraXVideoCapturer.IDLE_FPS)

                throttle.onHealth(health(Level.THROTTLE), reading)
                assertEquals(CameraXVideoCapturer.IDLE_FPS, capturer.targetFps)
                capturer.setPowerSaving(true)
                capturer.setPowerSaving(false)
                assertEquals(CameraXVideoCapturer.IDLE_FPS, capturer.targetFps)
                assertEquals(idleInterval * 2, pipeline.detectIntervalMs)
                assertEquals(
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF,
                    activity.window.attributes.screenBrightness,
                )
                assertEquals(engagedSteps, steps.toList())

                throttle.onHealth(health(Level.PAUSE), reading)
                assertEquals(engagedSteps, steps.toList())

                throttle.onHealth(health(Level.OK), reading)
                assertEquals(recordingFps, capturer.targetFps)
                assertEquals(idleInterval, pipeline.detectIntervalMs)
                assertEquals(windowBefore, activity.window.attributes.screenBrightness)
                assertEquals(engagedSteps + releasedSteps, steps.toList())
            }
            } finally {
                instrumentation.runOnMainSync {
                    throttle.onHealth(health(Level.OK), reading)
                    capturer.dispose()
                    pipeline.close()
                }
            }
        } finally {
            activity.finish()
        }
    }

    private class UnusedLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun health(level: Level) = HealthState(
        AxisState(level, 0),
        AxisState(Level.OK, 0),
        AxisState(Level.OK, 0),
        Cap.NONE,
        null,
        0,
        null,
    )

    private companion object {
        val reading = HealthSample(
            atMs = 0,
            batteryPercent = 80,
            chargeCounterUah = 0,
            plugged = true,
            batteryTempC = 36f,
            thermalStatus = ThermalStatus.NONE,
            freeBytes = 1,
            floorBytes = 1,
            floorHeld = true,
            pinnedNearCeiling = false,
            storageProjectionDays = null,
        )
        val engagedSteps = listOf(
            ThermalThrottle.STEP_FPS to true,
            ThermalThrottle.STEP_LIVE_BITRATE to true,
            ThermalThrottle.STEP_SCREEN to true,
        )
        val releasedSteps = listOf(
            ThermalThrottle.STEP_SCREEN to false,
            ThermalThrottle.STEP_LIVE_BITRATE to false,
            ThermalThrottle.STEP_FPS to false,
        )
    }
}
