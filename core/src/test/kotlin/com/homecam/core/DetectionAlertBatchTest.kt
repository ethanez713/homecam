package com.homecam.core

import com.homecam.core.detect.DetectionAlertBatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionAlertBatchTest {

    private val batch = DetectionAlertBatch()

    @Test
    fun `a person and the sound they make within the hold are one alert`() {
        assertTrue(batch.add(listOf("person"), null, 0))
        assertFalse(batch.add(listOf("glass"), null, 6_000))

        assertEquals(listOf("person", "glass"), batch.flush()!!.labels)
        assertNull(batch.flush())
    }

    @Test
    fun `a second person within the hold is one alert that says two`() {
        assertTrue(batch.add(listOf("person"), null, 0))
        assertFalse(batch.add(listOf("person", "person"), null, 1_000))
        assertFalse(batch.add(listOf("person"), null, 2_000))

        assertEquals(listOf("person", "person"), batch.flush()!!.labels)
    }

    @Test
    fun `the first thumbnail seen is the one sent`() {
        val first = byteArrayOf(1)
        assertTrue(batch.add(listOf("glass"), null, 0))
        assertFalse(batch.add(listOf("person"), first, 1_000))
        assertFalse(batch.add(listOf("person"), byteArrayOf(2), 2_000))

        assertArrayEquals(first, batch.flush()!!.thumbnail)
    }

    @Test
    fun `someone staying in view after the flush alerts nothing more`() {
        assertTrue(batch.add(listOf("person"), null, 0))
        batch.flush()

        assertFalse(batch.add(listOf("person"), null, 20_000))
        assertNull(batch.flush())
    }

    @Test
    fun `a new category during an existing visit opens a new batch`() {
        batch.add(listOf("person"), null, 0)
        batch.flush()

        assertTrue(batch.add(listOf("glass"), null, 120_000))
        assertEquals(listOf("glass"), batch.flush()!!.labels)
    }

    @Test
    fun `coming back after the quiet period is a new visit`() {
        batch.add(listOf("person"), null, 0)
        batch.flush()

        assertTrue(batch.add(listOf("person"), null, 300_001))
    }
}
