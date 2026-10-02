package com.homecam.core

import com.homecam.core.health.Cap
import com.homecam.core.health.RecordingGate
import com.homecam.proto.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingGateTest {
    private val now = 1_000_000L

    private fun keep(
        mode: RecordingMode,
        cap: Cap = Cap.NONE,
        armed: Boolean = true,
        manualUntil: Long = 0,
        detectionUntil: Long = 0,
    ) = RecordingGate.shouldKeepRecording(mode, cap, armed, now, manualUntil, detectionUntil)

    @Test
    fun `continuous records with nothing detected until a cap forbids it`() {
        assertEquals(true, keep(RecordingMode.CONTINUOUS))
        assertEquals(false, keep(RecordingMode.CONTINUOUS, Cap.NO_CONTINUOUS))
        assertEquals(false, keep(RecordingMode.CONTINUOUS, Cap.NO_RECORDING))
    }

    @Test
    fun `a detection still records under no-continuous and not under no-recording`() {
        val tail = now + 5_000
        assertEquals(true, keep(RecordingMode.CONTINUOUS, Cap.NO_CONTINUOUS, detectionUntil = tail))
        assertEquals(false, keep(RecordingMode.CONTINUOUS, Cap.NO_CONTINUOUS, detectionUntil = now - 1))
        assertEquals(false, keep(RecordingMode.CONTINUOUS, Cap.NO_RECORDING, detectionUntil = tail))
    }

    @Test
    fun `detection mode needs the camera armed and a live tail`() {
        assertEquals(true, keep(RecordingMode.DETECTION, detectionUntil = now + 1))
        assertEquals(false, keep(RecordingMode.DETECTION, armed = false, detectionUntil = now + 1))
        assertEquals(false, keep(RecordingMode.DETECTION))
    }

    @Test
    fun `a manual clip overrides the mode but never a no-recording cap`() {
        assertEquals(true, keep(RecordingMode.OFF, manualUntil = now + 1))
        assertEquals(true, keep(RecordingMode.CONTINUOUS, Cap.NO_CONTINUOUS, manualUntil = now + 1))
        assertEquals(false, keep(RecordingMode.OFF, Cap.NO_RECORDING, manualUntil = now + 1))
        assertEquals(false, keep(RecordingMode.OFF, manualUntil = now - 1))
    }

    @Test
    fun `lifting the cap resumes the requested mode with no other input`() {
        val sequence = listOf(Cap.NONE, Cap.NO_CONTINUOUS, Cap.NO_CONTINUOUS, Cap.NONE)
            .map { keep(RecordingMode.CONTINUOUS, it) }
        assertEquals(listOf(true, false, false, true), sequence)
    }
}
