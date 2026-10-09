package com.homecam.app

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CapabilityProvider
import com.homecam.app.camera.LensSource
import com.homecam.app.media.ContinuousCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A lens the camera advertises has to open and deliver frames, or it should not be advertised. */
@RunWith(AndroidJUnit4::class)
class LensEnumerationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var owner: LifecycleOwner
    private lateinit var capture: ContinuousCapture

    @Before
    fun setUp() {
        val registry = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        owner = registry
        instrumentation.runOnMainSync { (registry.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.STARTED }
        capture = ContinuousCapture(context, owner)
    }

    @After
    fun tearDown() {
        capture.stop()
        instrumentation.runOnMainSync { (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED }
    }

    @Test
    fun theBackWideAndFrontLensesExistAndMultiLensFollowsTheCount() {
        val caps = CapabilityProvider.capabilities(context)

        val ids = caps.lensesList.map { it.id }
        assertTrue("lenses were $ids", "back-wide" in ids && "front" in ids)
        assertEquals(caps.lensesCount > 1, caps.multiLens)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("front has a torch", caps.lensesList.single { it.id == "front" }.torch.not())
    }

    @Test
    fun everyAdvertisedLensOpensAndDeliversFramesWithinThreeSeconds() {
        val lenses = LensSource.lenses(context)
        assertTrue(lenses.isNotEmpty())
        capture.start()
        awaitFrames("initial capture", after = 0, within = START_TIMEOUT_MS)

        lenses.forEach { lens ->
            Log.i(TAG, "lens ${lens.id}: reach=${lens.reach} camera=${lens.cameraId} physical=${lens.physicalId} view=${lens.relativeView}")
            val before = capture.frameCount
            capture.setLens(lens)
            awaitFrames(lens.id, after = before + FRAMES, within = SWITCH_TIMEOUT_MS)
        }
    }

    private fun awaitFrames(what: String, after: Long, within: Long) {
        val deadline = System.currentTimeMillis() + within
        while (capture.frameCount <= after + FRAMES && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("$what delivered no frames within ${within}ms (error=${capture.lastCameraErrorCode})", capture.frameCount > after + FRAMES)
    }

    private companion object {
        const val TAG = "LensEnumerationTest"
        const val FRAMES = 5
        const val START_TIMEOUT_MS = 6_000L
        const val SWITCH_TIMEOUT_MS = 3_000L
    }
}
