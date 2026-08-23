package com.homecam.core

import com.homecam.core.detect.Detection
import com.homecam.core.detect.DetectionPolicy
import com.homecam.core.detect.MotionGate
import com.homecam.proto.DetectionMode
import com.homecam.proto.DetectionSchedule
import com.homecam.proto.DetectionZone
import com.homecam.proto.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MONDAY = 0
private const val TUESDAY = 1
private const val SUNDAY = 6

private fun everyDay() = (1 shl 7) - 1

private fun window(start: Int, end: Int, days: Int = everyDay()) =
    DetectionSchedule.Window.newBuilder()
        .setStartMinute(start).setEndMinute(end).setDaysMask(days).build()

private fun schedule(vararg windows: DetectionSchedule.Window) =
    DetectionSchedule.newBuilder().setEnabled(true).addAllWindows(windows.toList()).build()

private fun detection(label: String, confidence: Float = 0.9f, x: Float = 0.5f, y: Float = 0.5f) =
    Detection(label, confidence, x - 0.05f, y - 0.05f, x + 0.05f, y + 0.05f)

class DetectionPolicyTest {

    private val allOn = DetectionMode.newBuilder()
        .setPerson(true).setVehicle(true).setPet(true).setPackage(true)
        .setMinConfidence(0.5f)
        .build()

    @Test
    fun `coco classes map onto the four categories the product talks about`() {
        assertEquals("person", DetectionPolicy.category("person"))
        assertEquals("vehicle", DetectionPolicy.category("Truck"))
        assertEquals("pet", DetectionPolicy.category("dog"))
        assertEquals("package", DetectionPolicy.category("backpack"))
        assertNull(DetectionPolicy.category("toothbrush"))
    }

    @Test
    fun `a class nobody asked for is not an event`() {
        val personOnly = allOn.toBuilder().setPet(false).build()
        assertNull(DetectionPolicy.accepts(personOnly, DetectionZone.getDefaultInstance(), detection("dog")))
        assertEquals(
            "person",
            DetectionPolicy.accepts(personOnly, DetectionZone.getDefaultInstance(), detection("person")),
        )
    }

    @Test
    fun `a detection below the confidence floor is dropped`() {
        assertNull(
            DetectionPolicy.accepts(
                allOn, DetectionZone.getDefaultInstance(), detection("person", confidence = 0.4f),
            )
        )
    }

    @Test
    fun `no schedule means always`() {
        assertTrue(DetectionPolicy.isWithinSchedule(DetectionSchedule.getDefaultInstance(), 0, MONDAY))
        assertTrue(
            DetectionPolicy.isWithinSchedule(
                DetectionSchedule.newBuilder().setEnabled(true).build(), 720, MONDAY,
            )
        )
    }

    @Test
    fun `a daytime window covers only its own hours`() {
        val nineToFive = schedule(window(9 * 60, 17 * 60))
        assertTrue(DetectionPolicy.isWithinSchedule(nineToFive, 10 * 60, MONDAY))
        assertFalse(DetectionPolicy.isWithinSchedule(nineToFive, 8 * 60, MONDAY))
        assertFalse(DetectionPolicy.isWithinSchedule(nineToFive, 17 * 60, MONDAY))
    }

    @Test
    fun `an overnight window wraps past midnight`() {
        // 22:00 Monday to 06:00 Tuesday, armed on Mondays only.
        val overnight = schedule(window(22 * 60, 6 * 60, days = 1 shl MONDAY))
        assertTrue(DetectionPolicy.isWithinSchedule(overnight, 23 * 60, MONDAY))
        assertTrue(DetectionPolicy.isWithinSchedule(overnight, 2 * 60, TUESDAY))
        assertFalse(DetectionPolicy.isWithinSchedule(overnight, 2 * 60, MONDAY))
        assertFalse(DetectionPolicy.isWithinSchedule(overnight, 12 * 60, MONDAY))
    }

    @Test
    fun `an overnight window on sunday spills into monday`() {
        val overnight = schedule(window(23 * 60, 5 * 60, days = 1 shl SUNDAY))
        assertTrue(DetectionPolicy.isWithinSchedule(overnight, 23 * 60 + 30, SUNDAY))
        assertTrue(DetectionPolicy.isWithinSchedule(overnight, 60, MONDAY))
    }

    @Test
    fun `a zone judges the centre of a detection not its overlap`() {
        val leftHalf = DetectionZone.newBuilder()
            .setEnabled(true)
            .addAllPoints(
                listOf(
                    Point.newBuilder().setX(0f).setY(0f).build(),
                    Point.newBuilder().setX(0.5f).setY(0f).build(),
                    Point.newBuilder().setX(0.5f).setY(1f).build(),
                    Point.newBuilder().setX(0f).setY(1f).build(),
                )
            )
            .build()

        assertTrue(DetectionPolicy.isWithinZone(leftHalf, detection("person", x = 0.25f)))
        assertFalse(DetectionPolicy.isWithinZone(leftHalf, detection("person", x = 0.75f)))
        // Straddles the boundary but is centred outside, so it does not count.
        assertFalse(
            DetectionPolicy.isWithinZone(
                leftHalf, Detection("person", 0.9f, 0.4f, 0.4f, 0.9f, 0.6f),
            )
        )
    }

    @Test
    fun `a degenerate zone is treated as no zone rather than as blocking everything`() {
        val twoPoints = DetectionZone.newBuilder()
            .setEnabled(true)
            .addPoints(Point.newBuilder().setX(0f).setY(0f))
            .addPoints(Point.newBuilder().setX(1f).setY(1f))
            .build()
        assertTrue(DetectionPolicy.isWithinZone(twoPoints, detection("person")))
    }
}

class MotionGateTest {

    private val still = IntArray(64) { 100 }

    @Test
    fun `the first frame never triggers - there is nothing to compare against`() {
        assertFalse(MotionGate().accept(still, nowMs = 0))
    }

    @Test
    fun `a still scene keeps the gate shut`() {
        val gate = MotionGate()
        gate.accept(still, 0)
        assertFalse(gate.accept(still.copyOf(), 100))
        assertFalse(gate.accept(still.copyOf(), 200))
    }

    @Test
    fun `sensor noise below the per-cell threshold is not motion`() {
        val gate = MotionGate()
        gate.accept(still, 0)
        val noisy = IntArray(64) { 100 + (it % 3) }
        assertFalse(gate.accept(noisy, 100))
    }

    @Test
    fun `a moving subject opens the gate`() {
        val gate = MotionGate()
        gate.accept(still, 0)
        val moved = still.copyOf().also { for (i in 0 until 16) it[i] = 200 }
        assertTrue(gate.accept(moved, 100))
    }

    @Test
    fun `the gate holds open across a pause so one visit is one event`() {
        val gate = MotionGate(holdMs = 3_000)
        gate.accept(still, 0)
        val moved = still.copyOf().also { for (i in 0 until 16) it[i] = 200 }
        assertTrue(gate.accept(moved, 100))

        // Somebody standing still between steps: no frame difference at all.
        assertTrue(gate.accept(moved.copyOf(), 1_000))
        assertTrue(gate.accept(moved.copyOf(), 3_000))
        assertFalse(gate.accept(moved.copyOf(), 3_200))
    }

    @Test
    fun `a resized grid is treated as a fresh start rather than as motion`() {
        val gate = MotionGate()
        gate.accept(still, 0)
        assertFalse(gate.accept(IntArray(16) { 100 }, 100))
    }
}
