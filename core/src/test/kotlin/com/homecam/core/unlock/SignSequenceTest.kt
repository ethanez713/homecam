package com.homecam.core.unlock

import com.homecam.core.unlock.SignSequence.Event
import com.homecam.core.unlock.SignSequence.Event.Opened
import com.homecam.core.unlock.SignSequence.Event.Reset
import com.homecam.core.unlock.SignSequence.Event.SignAccepted
import com.homecam.core.unlock.SignSequence.Event.Submitted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignSequenceTest {

    @Test
    fun `a repeated sign needs a fist between`() {
        val hands = Hands()
        assertEquals(Opened, hands.show(0))
        assertEquals(SignAccepted(0), hands.show(4))
        assertNull(hands.show(4))
        assertNull(hands.show(0))
        assertEquals(SignAccepted(1), hands.show(4))
    }

    @Test
    fun `a flicker shorter than holdMs is ignored`() {
        val seq = SignSequence()
        assertEquals(Opened, seq.held(0, 0))

        assertNull(seq.feed(1_000, 1))
        assertNull(seq.feed(1_400, null))
        seq.feed(1_401, null)

        seq.feed(2_000, 2)
        assertNull(seq.feed(2_399, 2))
        seq.feed(2_400, null)
        seq.feed(2_401, null)

        assertEquals(SignAccepted(0), seq.held(3, 3_000))
    }

    @Test
    fun `a lone null frame inside a hold is tolerated and two consecutive nulls break it`() {
        val kept = SignSequence()
        assertEquals(Opened, kept.held(0, 0))
        kept.feed(1_000, 5)
        assertNull(kept.feed(1_200, null))
        assertEquals(SignAccepted(0), kept.feed(1_400, 5))

        val broken = SignSequence()
        assertEquals(Opened, broken.held(0, 0))
        broken.feed(1_000, 5)
        broken.feed(1_100, null)
        broken.feed(1_200, null)
        assertNull(broken.feed(1_400, 5))
        assertEquals(SignAccepted(0), broken.feed(1_800, 5))
    }

    @Test
    fun `holding a sign longer does not accept it twice`() {
        val hands = Hands()
        assertEquals(Opened, hands.show(0))
        assertEquals(SignAccepted(0), hands.show(2))
        assertNull(hands.keep(2, 200))
        assertNull(hands.keep(2, 2_000))
    }

    @Test
    fun `8 s idle emits Reset once`() {
        val seq = SignSequence()
        assertEquals(Opened, seq.held(0, 0))
        assertNull(seq.feed(8_000, 2))
        assertNull(seq.feed(8_399, null))
        assertEquals(Reset, seq.feed(8_400, 2))
        assertNull(seq.feed(20_000, null))
        assertNull(seq.held(2, 21_000))
        assertEquals(Opened, seq.held(0, 22_000))

        val later = SignSequence()
        assertEquals(Opened, later.held(0, 0))
        assertEquals(SignAccepted(0), later.held(4, 1_000))
        assertNull(later.held(0, 6_000))
        assertNull(later.feed(9_399, null))
        assertEquals(Reset, later.feed(9_400, null))
        assertNull(later.feed(15_000, null))
    }

    @Test
    fun `the sixth sign submits and the machine returns to idle`() {
        val hands = Hands()
        val code = listOf(1, 2, 3, 4, 5, 1)
        assertEquals(Opened, hands.show(0))
        code.forEachIndexed { index, sign ->
            assertNull(hands.show(0))
            val event = hands.show(sign)
            if (index < code.lastIndex) {
                assertEquals(SignAccepted(index), event)
            } else {
                val submitted = event as Submitted
                assertEquals(code, submitted.signs)
            }
        }
        assertNull(hands.show(2))
        assertEquals(Opened, hands.show(0))
        assertEquals(SignAccepted(0), hands.show(3))
    }

    @Test
    fun `signs before the opening fist are ignored`() {
        val hands = Hands()
        assertNull(hands.show(1))
        assertNull(hands.show(5))
        assertEquals(Opened, hands.show(0))
        assertEquals(SignAccepted(0), hands.show(1))
    }

    @Test
    fun `Submitted toString() contains no digit`() {
        val signs = listOf(1, 2, 3, 4, 5, 1)
        val text = Submitted(signs).toString()
        assertEquals("Submitted(6 signs)", text)
        assertTrue(signs.none { digit -> text.contains(digit.toString()) })
    }

    @Test
    fun `a frame earlier than the previous one is ignored`() {
        val seq = SignSequence()
        seq.feed(1_000, 0)
        assertNull(seq.feed(500, 0))
        assertNull(seq.feed(900, 5))
        assertEquals(Opened, seq.feed(1_400, 0))
    }

    private fun SignSequence.held(count: Int, at: Long): Event? {
        val first = feed(at, count)
        val second = feed(at + HOLD_MS, count)
        check(first == null) { "first frame of $count at $at returned $first" }
        return second
    }

    private class Hands {
        val seq = SignSequence()
        private var at = 0L

        fun show(count: Int): Event? {
            breakRun()
            val started = seq.feed(at, count)
            at += HOLD_MS
            val finished = seq.feed(at, count)
            check(started == null) { "first frame of $count returned $started" }
            return finished
        }

        fun keep(count: Int, ms: Long): Event? {
            at += ms
            return seq.feed(at, count)
        }

        private fun breakRun() {
            at += 1
            seq.feed(at, null)
            at += 1
            seq.feed(at, null)
            at += 1
        }
    }

    private companion object {
        const val HOLD_MS = 400L
    }
}
