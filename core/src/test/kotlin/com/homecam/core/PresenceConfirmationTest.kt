package com.homecam.core

import com.homecam.core.detect.PresenceConfirmation
import com.homecam.core.detect.PresenceConfirmation.Action
import com.homecam.core.detect.PresenceConfirmation.Discounted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceConfirmationTest {

    private val presence = PresenceConfirmation(setOf("person"))
    private val person = setOf("person")

    @Test
    fun `a single sighting is discounted`() {
        presence.observe(0, person)
        assertTrue(presence.observe(3_999, emptySet()).discounted.isEmpty())

        val end = presence.observe(4_000, emptySet())
        assertEquals(listOf(Discounted("person", sightings = 1, spanMs = 0)), end.discounted)
        assertTrue(end.actions.isEmpty())
    }

    @Test
    fun `a burst spanning 800 ms is discounted`() {
        listOf(0L, 200L, 400L, 600L, 800L).forEach { at ->
            val step = presence.observe(at, person)
            assertEquals(Action.Hold, step.actions.getValue("person"))
            assertTrue(step.discounted.isEmpty())
        }

        val end = presence.observe(4_800, emptySet())
        assertEquals(listOf(Discounted("person", sightings = 5, spanMs = 800)), end.discounted)
    }

    @Test
    fun `sightings every 200 ms confirm at the frame where the span reaches 1 s`() {
        val steps = (0L..1_000L step 200).map { presence.observe(it, person) }

        assertTrue(steps.all { it.discounted.isEmpty() })
        assertEquals(
            listOf(
                Action.Hold,
                Action.Hold,
                Action.Hold,
                Action.Hold,
                Action.Hold,
                Action.Confirm(priorSightings = 5),
            ),
            steps.map { it.actions.getValue("person") },
        )
    }

    @Test
    fun `two sightings 3 s apart confirm`() {
        assertEquals(Action.Hold, presence.observe(0, person).actions.getValue("person"))

        val second = presence.observe(3_000, person)
        assertEquals(Action.Confirm(priorSightings = 1), second.actions.getValue("person"))
        assertTrue(second.discounted.isEmpty())
    }

    @Test
    fun `two sightings 5 s apart are two discounted episodes`() {
        val first = presence.observe(0, person)
        val second = presence.observe(5_000, person)
        val closed = presence.observe(9_000, emptySet())

        assertEquals(Action.Hold, first.actions.getValue("person"))
        assertTrue(first.discounted.isEmpty())
        assertEquals(Action.Hold, second.actions.getValue("person"))
        assertEquals(listOf(Discounted("person", sightings = 1, spanMs = 0)), second.discounted)
        assertEquals(listOf(Discounted("person", sightings = 1, spanMs = 0)), closed.discounted)
    }

    @Test
    fun `after confirmation sightings pass immediately`() {
        presence.observe(0, person)
        presence.observe(1_000, person)

        val passed = presence.observe(1_200, person)
        assertEquals(Action.Pass, passed.actions.getValue("person"))
        assertTrue(passed.discounted.isEmpty())
        assertEquals(Action.Pass, presence.observe(1_200 + 3_999, person).actions.getValue("person"))
    }

    @Test
    fun `after 4 s with no sighting the next sighting starts a new unconfirmed episode`() {
        presence.observe(0, person)
        presence.observe(1_000, person)

        val next = presence.observe(5_000, person)
        assertEquals(Action.Hold, next.actions.getValue("person"))
        assertTrue(next.discounted.isEmpty())

        val end = presence.observe(9_000, emptySet())
        assertEquals(listOf(Discounted("person", sightings = 1, spanMs = 0)), end.discounted)
    }

    @Test
    fun `other categories always pass`() {
        val others = setOf("vehicle", "pet", "package", "sound")
        val ignored = presence.observe(0, others)
        assertTrue(ignored.actions.isEmpty())
        assertTrue(ignored.discounted.isEmpty())

        val mixed = presence.observe(100, others + "person")
        assertEquals(setOf("person"), mixed.actions.keys)
        assertEquals(Action.Hold, mixed.actions.getValue("person"))

        val between = presence.observe(500, others)
        assertTrue(between.actions.isEmpty())
        assertTrue(between.discounted.isEmpty())
        assertEquals(Action.Hold, presence.observe(600, person).actions.getValue("person"))

        val vehicles = PresenceConfirmation(setOf("vehicle"))
        val heldVehicle = vehicles.observe(0, setOf("person", "vehicle", "pet", "package", "sound"))
        assertEquals(setOf("vehicle"), heldVehicle.actions.keys)
        assertEquals(Action.Hold, heldVehicle.actions.getValue("vehicle"))
    }
}
