package com.homecam.core

import com.homecam.core.health.Cap
import com.homecam.core.health.HealthProtocol
import com.homecam.core.health.RecordingTimer
import com.homecam.proto.RecordingMode
import com.homecam.proto.RecordingModeSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingTimerTest {
    private val hour = 60 * 60_000L

    private fun ask(mode: RecordingMode, minutes: Int = 0, revertTo: RecordingMode = RecordingMode.DETECTION) =
        RecordingModeSetting.newBuilder().setMode(mode).setRevertAfterMinutes(minutes).setRevertTo(revertTo).build()

    @Test
    fun `a request without a duration schedules nothing`() {
        val (mode, revert) = RecordingTimer.applyRequest(ask(RecordingMode.CONTINUOUS), nowMs = 1_000)
        assertEquals(RecordingMode.CONTINUOUS, mode)
        assertNull(revert)
    }

    @Test
    fun `a timed continuous reverts at the stored time and not before`() {
        val start = 5_000_000L
        val (mode, revert) = RecordingTimer.applyRequest(ask(RecordingMode.CONTINUOUS, minutes = 60), start)
        assertEquals(RecordingMode.CONTINUOUS, mode)
        assertEquals(start + hour, revert!!.atMs)
        assertEquals(RecordingMode.DETECTION, revert.to)

        assertNull(RecordingTimer.dueMode(revert, start + hour - 1))
        assertEquals(RecordingMode.DETECTION, RecordingTimer.dueMode(revert, start + hour))
    }

    @Test
    fun `a cap during the window neither extends nor cancels the revert`() {
        val start = 0L
        val (_, revert) = RecordingTimer.applyRequest(ask(RecordingMode.CONTINUOUS, minutes = 60), start)
        val dueAt = listOf(Cap.NONE, Cap.NO_CONTINUOUS, Cap.NO_RECORDING, Cap.NO_CONTINUOUS, Cap.NONE)
            .withIndex()
            .map { (i, cap) ->
                HealthProtocol.effectiveMode(RecordingMode.CONTINUOUS, cap)
                RecordingTimer.dueMode(revert, start + i * 20 * 60_000L)
            }
        assertEquals(listOf(null, null, null, RecordingMode.DETECTION, RecordingMode.DETECTION), dueAt)
    }

    @Test
    fun `the duration is clamped to a day and a revert to the same mode is not scheduled`() {
        val (_, revert) = RecordingTimer.applyRequest(ask(RecordingMode.CONTINUOUS, minutes = 100_000), 0)
        assertEquals(24 * hour, revert!!.atMs)

        val (_, same) = RecordingTimer.applyRequest(
            ask(RecordingMode.CONTINUOUS, minutes = 60, revertTo = RecordingMode.CONTINUOUS), 0,
        )
        assertNull(same)
    }

    @Test
    fun `with no revert nothing is due`() {
        assertNull(RecordingTimer.dueMode(null, Long.MAX_VALUE))
    }
}
