package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CameraDiagnostics
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The diagnostics log's load-bearing claim: a process that was killed and restarted is
 * detectable after the fact, which is the whole content of GATE-C point 4. A clean stop
 * writes a service_stop; a kill does not, and the next run's start must say so.
 */
@RunWith(AndroidJUnit4::class)
class CameraDiagnosticsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val logFile get() = File(File(context.filesDir, "diagnostics"), "camera.jsonl")

    @Before
    fun clear() {
        File(context.filesDir, "diagnostics").deleteRecursively()
    }

    private fun lastStart(): JSONObject =
        logFile.readLines().map { JSONObject(it) }.last { it.getString("event") == "service_start" }

    @Test
    fun aKilledPreviousRunIsFlaggedOnTheNextStart() {
        CameraDiagnostics(context).start("manual") // no stop — as if killed
        CameraDiagnostics(context).start("restart")
        assertTrue("a start with no preceding stop must flag the prior run as unclean",
            lastStart().getBoolean("prev_run_unclean"))
    }

    @Test
    fun aCleanStopIsNotMistakenForAKill() {
        CameraDiagnostics(context).apply { start("manual"); stop("destroy") }
        CameraDiagnostics(context).start("restart")
        assertFalse("a start after a clean stop must not be flagged",
            lastStart().getBoolean("prev_run_unclean"))
    }
}
