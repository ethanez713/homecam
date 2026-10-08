package com.homecam.core

import com.homecam.core.detect.DetectionPolicy
import com.homecam.core.sound.SoundClasses
import com.homecam.core.sound.SoundGate
import com.homecam.proto.DetectionSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundDetectionTest {

    @Test
    fun `one loud sound produces exactly one event despite wobble and echo`() {
        val gate = SoundGate(thresholdDb = -20f, releaseMarginDb = 6f, refractoryMs = 4_000)
        // A bang: rises through the threshold, wobbles around it as it echoes, then decays.
        val series = listOf(-40f, -30f, -18f, -19f, -17f, -22f, -19f, -25f, -35f, -50f)
        var events = 0
        series.forEachIndexed { i, level -> if (gate.accept(level, i * 100L)) events++ }
        assertEquals("one bang, one event", 1, events)
    }

    @Test
    fun `a sustained level does not re-fire until it drops and the refractory passes`() {
        val gate = SoundGate(thresholdDb = -20f, releaseMarginDb = 6f, refractoryMs = 4_000)
        var events = 0
        // Held loud for 3 seconds: still one event, not thirty.
        for (t in 0..3_000 step 100) if (gate.accept(-10f, t.toLong())) events++
        assertEquals(1, events)
        // Drops quiet, then a fresh bang after the refractory window: a second event.
        assertFalse(gate.accept(-50f, 3_100))
        assertTrue(gate.accept(-10f, 8_000))
    }

    @Test
    fun `sound obeys the same schedule as vision`() {
        // A window covering 09:00-17:00 on all days; the shared policy suppresses outside it.
        val schedule = DetectionSchedule.newBuilder().setEnabled(true).addWindows(
            DetectionSchedule.Window.newBuilder()
                .setStartMinute(9 * 60).setEndMinute(17 * 60).setDaysMask(0b1111111)
        ).build()
        assertTrue(DetectionPolicy.isWithinSchedule(schedule, minuteOfDay = 12 * 60, dayOfWeek = 2))
        assertFalse(DetectionPolicy.isWithinSchedule(schedule, minuteOfDay = 3 * 60, dayOfWeek = 2))
    }

    @Test
    fun `audioset labels map onto the small user-facing set`() {
        assertEquals(SoundClasses.DOG, SoundClasses.categoryFor("Bark"))
        assertEquals(SoundClasses.ALARM, SoundClasses.categoryFor("Smoke detector, smoke alarm"))
        assertEquals(SoundClasses.GLASS, SoundClasses.categoryFor("Shatter"))
        assertNull("an unmapped class is not reported", SoundClasses.categoryFor("Piano"))
    }

    @Test
    fun `speech maps to voice and voice is not pushed by default`() {
        assertEquals(SoundClasses.VOICE, SoundClasses.categoryFor("Conversation"))
        assertTrue(SoundClasses.VOICE in SoundClasses.ALL)
        assertFalse(SoundClasses.VOICE in SoundClasses.pushSelection(null))
        assertFalse(SoundClasses.DOG in SoundClasses.pushSelection(null))
    }

    @Test
    fun `an unset selection pushes the concerning classes and an empty one pushes none`() {
        assertEquals(setOf("alarm", "doorbell", "glass", "shout", "knock"), SoundClasses.pushSelection(null))
        assertEquals(emptySet<String>(), SoundClasses.pushSelection(emptyList()))
        assertEquals(setOf("voice"), SoundClasses.pushSelection(listOf("voice")))
    }

    @Test
    fun `a sound matching no selected class is not pushed, but is pushed generically without a classifier`() {
        assertNull(SoundClasses.pushCategory(classifierAvailable = true, recognised = null))
        assertEquals("glass", SoundClasses.pushCategory(classifierAvailable = true, recognised = "glass"))
        assertEquals("sound", SoundClasses.pushCategory(classifierAvailable = false, recognised = null))
    }
}
