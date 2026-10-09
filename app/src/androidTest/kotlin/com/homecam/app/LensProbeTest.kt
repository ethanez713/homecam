package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.LensProbe
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A short probe, to prove it completes and reports in a well-formed event on any phone. */
@RunWith(AndroidJUnit4::class)
class LensProbeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theProbeRunsToCompletionAndDescribesTheCameras() {
        val result = LensProbe(context, trialSeconds = SHORT_TRIAL_SECONDS).run()

        assertTrue("no cameras were listed", result.getJSONArray("cameras").length() > 0)
        assertTrue(result.has("platform_concurrent_ids") && result.has("camerax_concurrent_ids"))
        assertTrue(result.has("encoders"))
        val sets = result.getJSONArray("sets")
        for (i in 0 until sets.length()) {
            val set = sets.getJSONObject(i)
            assertTrue(set.has("feasible") && set.getJSONArray("trials").length() > 0)
            if (set.getBoolean("feasible")) assertTrue(set.getString("best").contains("x"))
        }
        println("lens_probe: $result")
    }

    private companion object {
        const val SHORT_TRIAL_SECONDS = 10
    }
}
