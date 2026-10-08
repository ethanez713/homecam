package com.homecam.core

import com.homecam.core.detect.AlertUrgency
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertUrgencyTest {

    private val urgency = AlertUrgency()

    @Test
    fun `a person then a distress sound is urgent`() {
        assertFalse(urgency.observe(listOf("person"), 0))
        assertTrue(urgency.observe(listOf("glass"), 10_000))
    }

    @Test
    fun `a distress sound then a person is urgent`() {
        assertFalse(urgency.observe(listOf("shout"), 0))
        assertTrue(urgency.observe(listOf("person"), 10_000))
    }

    @Test
    fun `an alarm counts as a distress sound`() {
        urgency.observe(listOf("person"), 0)
        assertTrue(urgency.observe(listOf("alarm"), 1_000))
    }

    @Test
    fun `two different distress sounds are urgent without a person`() {
        urgency.observe(listOf("glass"), 0)
        assertTrue(urgency.observe(listOf("shout"), 20_000))
    }

    @Test
    fun `the same distress sound twice is not urgent`() {
        urgency.observe(listOf("glass"), 0)
        assertFalse(urgency.observe(listOf("glass"), 20_000))
    }

    @Test
    fun `a person with an ordinary sound is not urgent`() {
        urgency.observe(listOf("person"), 0)
        assertFalse(urgency.observe(listOf("voice"), 1_000))
        assertFalse(urgency.observe(listOf("knock"), 2_000))
        assertFalse(urgency.observe(listOf("dog"), 3_000))
    }

    @Test
    fun `signals more than a minute apart are not a coincidence`() {
        urgency.observe(listOf("person"), 0)
        assertFalse(urgency.observe(listOf("glass"), AlertUrgency.WINDOW_MS + 1))
    }

    @Test
    fun `signals exactly a minute apart still coincide`() {
        urgency.observe(listOf("person"), 0)
        assertTrue(urgency.observe(listOf("glass"), AlertUrgency.WINDOW_MS))
    }

    @Test
    fun `a second coincidence inside the cooldown is not urgent`() {
        urgency.observe(listOf("person", "glass"), 0)
        assertFalse(urgency.observe(listOf("shout"), 30_000))
    }

    @Test
    fun `a coincidence after the cooldown is urgent again`() {
        assertTrue(urgency.observe(listOf("person", "glass"), 0))
        assertTrue(urgency.observe(listOf("person", "glass"), AlertUrgency.COOLDOWN_MS))
    }

    @Test
    fun `an unrelated observation after the cooldown does not fire a stale coincidence`() {
        assertTrue(urgency.observe(listOf("person", "glass"), 0))
        assertFalse(urgency.observe(listOf("voice"), AlertUrgency.COOLDOWN_MS + 1))
    }
}
