package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.AppGraph
import com.homecam.app.camera.LensProbe
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A short probe by default, to prove it completes and reports in a well-formed event on any
 * phone. `-e trialSeconds 120 -e logToDiagnostics true` is the real run for the camera phone.
 */
@RunWith(AndroidJUnit4::class)
class LensProbeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun theProbeRunsToCompletionAndDescribesTheCameras() {
        val trialSeconds = arguments.getString("trialSeconds")?.toInt() ?: SHORT_TRIAL_SECONDS
        val result = LensProbe(context, trialSeconds).run()
        if (arguments.getString("logToDiagnostics") == "true") AppGraph.get(context).diagnostics.lensProbe(result)

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
