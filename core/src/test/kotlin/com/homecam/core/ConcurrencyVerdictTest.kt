package com.homecam.core

import com.homecam.core.lens.ConcurrencyVerdict
import com.homecam.core.lens.StreamTrial
import com.homecam.core.lens.Trial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrencyVerdictTest {

    private fun stream(id: String, vararg fps: Float, error: String? = null) = StreamTrial(id, null, fps.toList(), error)

    private fun trial(
        vararg streams: StreamTrial,
        width: Int = 1280,
        height: Int = 720,
        seconds: Int = 30,
        ranMs: Long = seconds * 1000L,
        openError: String? = null,
    ) = Trial(width, height, seconds, ranMs, streams.toList(), tempRiseC = 1.5f, openError = openError)

    @Test
    fun `every stream holding the rate for the whole trial is feasible`() {
        assertTrue(ConcurrencyVerdict.feasible(trial(stream("0", 30f, 29f, 30f), stream("1", 28f, 30f, 30f))))
    }

    @Test
    fun `one window below the floor on one stream makes the set infeasible`() {
        assertFalse(ConcurrencyVerdict.feasible(trial(stream("0", 30f, 30f, 30f), stream("1", 30f, 23.9f, 30f))))
    }

    @Test
    fun `a camera error is infeasible however good the frame rate was`() {
        assertFalse(ConcurrencyVerdict.feasible(trial(stream("0", 30f, 30f, 30f, error = "disconnected"))))
    }

    @Test
    fun `a trial that stopped early or never opened proves nothing`() {
        assertFalse(ConcurrencyVerdict.feasible(trial(stream("0", 30f, 30f, 30f), ranMs = 20_000)))
        assertFalse(ConcurrencyVerdict.feasible(trial(openError = "camera 2 would not open")))
        assertFalse(ConcurrencyVerdict.feasible(trial(stream("0", 30f, 30f))))
    }

    @Test
    fun `the best size is the largest that passed`() {
        val small = trial(stream("0", 30f, 30f, 30f))
        val big = trial(stream("0", 30f, 30f, 30f), width = 1920, height = 1080)
        val failedBig = trial(stream("0", 12f, 12f, 12f), width = 3840, height = 2160)

        assertEquals(big, ConcurrencyVerdict.best(listOf(small, big, failedBig)))
        assertNull(ConcurrencyVerdict.best(listOf(failedBig)))
    }
}
