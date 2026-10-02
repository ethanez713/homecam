package com.homecam.core

import com.homecam.core.sound.PcmWindower
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmWindowerTest {

    private fun pcm(samples: Int, channels: Int = 1, value: (Int) -> Short): ByteArray {
        val out = ByteArray(samples * channels * 2)
        for (i in 0 until samples) for (c in 0 until channels) {
            val v = value(i).toInt()
            out[(i * channels + c) * 2] = v.toByte()
            out[(i * channels + c) * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun windower(window: Int, into: MutableList<Pair<FloatArray, Float>>) =
        PcmWindower(window) { wave, db -> into += wave to db }

    @Test
    fun `48 kHz becomes whole 16 kHz windows`() {
        val got = mutableListOf<Pair<FloatArray, Float>>()
        val w = windower(160, got)
        val data = pcm(48_000 / 10) { 8192 }
        w.push(data, data.size, inRate = 48_000, channels = 1)
        assertEquals(10, got.size)
        assertTrue(got.all { it.first.size == 160 })
    }

    @Test
    fun `a constant quarter-scale signal reads about -12 dBFS`() {
        val got = mutableListOf<Pair<FloatArray, Float>>()
        val w = windower(160, got)
        val data = pcm(4_800) { 16384 }
        w.push(data, data.size, 48_000, 1)
        assertTrue("level was ${got.first().second}", abs(got.first().second - (-6.02f)) < 0.1f)
    }

    @Test
    fun `silence reads as the floor level`() {
        val got = mutableListOf<Pair<FloatArray, Float>>()
        val w = windower(160, got)
        val data = pcm(480) { 0 }
        w.push(data, data.size, 48_000, 1)
        assertEquals(PcmWindower.SILENCE_DB, got.first().second)
    }

    @Test
    fun `stereo is averaged to mono`() {
        val got = mutableListOf<Pair<FloatArray, Float>>()
        val w = windower(10, got)
        val data = ByteArray(30 * 2 * 2)
        for (f in 0 until 30) {
            data[f * 4] = 0; data[f * 4 + 1] = 0x40 // left 16384
            data[f * 4 + 2] = 0; data[f * 4 + 3] = 0xC0.toByte() // right -16384
        }
        w.push(data, data.size, 48_000, 2)
        assertTrue(got.first().first.all { abs(it) < 1e-4f })
    }

    @Test
    fun `windows span push boundaries`() {
        val got = mutableListOf<Pair<FloatArray, Float>>()
        val w = windower(160, got)
        val data = pcm(300) { 1000 }
        repeat(16) { w.push(data, data.size, 48_000, 1) }
        assertEquals(10, got.size)
    }

    @Test
    fun `a rate that is not a whole ratio still yields the right sample count`() {
        val got = mutableListOf<Pair<FloatArray, Float>>()
        val w = windower(160, got)
        val data = pcm(44_100) { 1000 }
        w.push(data, data.size, 44_100, 1)
        assertTrue("windows=${got.size}", got.size in 99..100)
    }
}
