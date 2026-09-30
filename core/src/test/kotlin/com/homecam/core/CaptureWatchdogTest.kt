package com.homecam.core

import com.homecam.core.media.CaptureWatchdog
import com.homecam.core.media.CaptureWatchdog.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureWatchdogTest {

    private val watchdog = CaptureWatchdog()
    private var frames = 0L

    private fun sample(
        nowMs: Long,
        running: Boolean = true,
        frameCount: Long = frames,
    ): CaptureWatchdog.Observation {
        frames = frameCount
        return watchdog.observe(CaptureWatchdog.Sample(nowMs, running, frameCount))
    }

    @Test
    fun `the first sample only anchors the window`() {
        val observation = sample(nowMs = 0, frameCount = 0)
        assertNull(observation.signal)
        assertEquals(0f, observation.framesPerSecond, 0.001f)
        assertFalse(watchdog.isBlind)
    }

    @Test
    fun `frames keep it seeing and the rate is the interval`() {
        sample(nowMs = 0, frameCount = 0)
        val observation = sample(nowMs = 2_000, frameCount = 50)
        assertNull(observation.signal)
        assertEquals(25f, observation.framesPerSecond, 0.001f)
        assertFalse(watchdog.isBlind)
    }

    @Test
    fun `silence short of the window is not blindness`() {
        sample(nowMs = 0, frameCount = 10)
        sample(nowMs = 1_000, frameCount = 10)
        val observation = sample(nowMs = 14_999, frameCount = 10)
        assertNull(observation.signal)
        assertEquals(0f, observation.framesPerSecond, 0.001f)
        assertFalse(watchdog.isBlind)
    }

    @Test
    fun `silence for the window goes blind once`() {
        sample(nowMs = 0, frameCount = 4)
        sample(nowMs = 1_000, frameCount = 4)
        val blind = sample(nowMs = 16_000, frameCount = 4)
        assertEquals(Signal.BECAME_BLIND, blind.signal)
        assertTrue(watchdog.isBlind)

        val again = sample(nowMs = 18_000, frameCount = 4)
        assertNull(again.signal)
        assertTrue(watchdog.isBlind)
    }

    @Test
    fun `the window is measured from the last frame`() {
        sample(nowMs = 0, frameCount = 0)
        sample(nowMs = 10_000, frameCount = 30)
        val notYet = sample(nowMs = 24_999, frameCount = 30)
        assertNull(notYet.signal)
        val blind = sample(nowMs = 25_000, frameCount = 30)
        assertEquals(Signal.BECAME_BLIND, blind.signal)
    }

    @Test
    fun `a recovery attempt that brings no frames escalates once`() {
        sample(nowMs = 0, frameCount = 1)
        sample(nowMs = 15_000, frameCount = 1)
        watchdog.noteRecoveryAttempt(15_000)

        assertNull(sample(nowMs = 29_999, frameCount = 1).signal)
        assertEquals(Signal.ESCALATE, sample(nowMs = 30_000, frameCount = 1).signal)
        assertNull(sample(nowMs = 45_000, frameCount = 1).signal)
        assertTrue(watchdog.isBlind)
    }

    @Test
    fun `silence never escalates without a recovery attempt`() {
        sample(nowMs = 0, frameCount = 1)
        sample(nowMs = 15_000, frameCount = 1)
        assertNull(sample(nowMs = 60_000, frameCount = 1).signal)
        assertTrue(watchdog.isBlind)
    }

    @Test
    fun `an attempt while seeing is ignored`() {
        sample(nowMs = 0, frameCount = 1)
        watchdog.noteRecoveryAttempt(1_000)
        sample(nowMs = 15_000, frameCount = 1)
        assertNull(sample(nowMs = 40_000, frameCount = 1).signal)
    }

    @Test
    fun `a second recovery attempt does not move the deadline`() {
        sample(nowMs = 0, frameCount = 1)
        sample(nowMs = 15_000, frameCount = 1)
        watchdog.noteRecoveryAttempt(15_000)
        watchdog.noteRecoveryAttempt(25_000)
        assertEquals(Signal.ESCALATE, sample(nowMs = 30_000, frameCount = 1).signal)
    }

    @Test
    fun `a frame after blindness is a recovery and the window starts over`() {
        sample(nowMs = 0, frameCount = 1)
        sample(nowMs = 15_000, frameCount = 1)
        watchdog.noteRecoveryAttempt(15_000)

        val recovered = sample(nowMs = 20_000, frameCount = 8)
        assertEquals(Signal.RECOVERED, recovered.signal)
        assertEquals(1.4f, recovered.framesPerSecond, 0.001f)
        assertFalse(watchdog.isBlind)

        assertNull(sample(nowMs = 34_999, frameCount = 8).signal)
        assertEquals(Signal.BECAME_BLIND, sample(nowMs = 35_000, frameCount = 8).signal)
        watchdog.noteRecoveryAttempt(35_000)
        assertEquals(Signal.ESCALATE, sample(nowMs = 50_000, frameCount = 8).signal)
    }

    @Test
    fun `a stopped capture is not blind and is not a recovery`() {
        sample(nowMs = 0, frameCount = 1)
        sample(nowMs = 15_000, frameCount = 1)
        assertTrue(watchdog.isBlind)

        val stopped = sample(nowMs = 40_000, frameCount = 1, running = false)
        assertNull(stopped.signal)
        assertEquals(0f, stopped.framesPerSecond, 0.001f)
        assertTrue(watchdog.isBlind)
    }

    @Test
    fun `running again after a stop gets a fresh window`() {
        sample(nowMs = 0, frameCount = 5)
        sample(nowMs = 1_000, frameCount = 5)
        sample(nowMs = 2_000, frameCount = 5, running = false)

        val restarted = sample(nowMs = 60_000, frameCount = 5, running = true)
        assertNull(restarted.signal)
        assertFalse(watchdog.isBlind)
        assertNull(sample(nowMs = 74_999, frameCount = 5).signal)
        assertEquals(Signal.BECAME_BLIND, sample(nowMs = 75_000, frameCount = 5).signal)
    }

    @Test
    fun `a frame as capture starts running again recovers`() {
        sample(nowMs = 0, frameCount = 1)
        sample(nowMs = 15_000, frameCount = 1)
        sample(nowMs = 16_000, frameCount = 1, running = false)

        val restarted = sample(nowMs = 17_000, frameCount = 6, running = true)
        assertEquals(Signal.RECOVERED, restarted.signal)
        assertFalse(watchdog.isBlind)
    }

    @Test
    fun `a count that went backwards is not a frame`() {
        sample(nowMs = 0, frameCount = 10)
        val dropped = sample(nowMs = 1_000, frameCount = 3)
        assertNull(dropped.signal)
        assertEquals(0f, dropped.framesPerSecond, 0.001f)
        assertEquals(Signal.BECAME_BLIND, sample(nowMs = 15_000, frameCount = 3).signal)
    }

    @Test
    fun `a stopped capture reports no rate even if the count jumped`() {
        sample(nowMs = 0, frameCount = 0, running = true)
        val stopped = sample(nowMs = 2_000, frameCount = 40, running = false)
        assertEquals(0f, stopped.framesPerSecond, 0.001f)
        assertNull(stopped.signal)
    }
}
