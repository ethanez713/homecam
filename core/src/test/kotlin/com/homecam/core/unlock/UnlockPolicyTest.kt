package com.homecam.core.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockPolicyTest {

    private val code = listOf(1, 2, 3, 4, 5, 1)
    private val wrong = listOf(5, 4, 3, 2, 1, 5)

    @Test
    fun `the right code unlocks and resets the state`() {
        val state = UnlockState(failures = 2, lockouts = 4, lockedUntilMs = 10)
        val decision = UnlockPolicy.attempt(code, code, state, nowMs = 10)
        assertEquals(UnlockOutcome.UNLOCKED, decision.outcome)
        assertEquals(UnlockState(), decision.state)
    }

    @Test
    fun `three wrong codes start a 15 minute lockout`() {
        val decision = failToLock(UnlockState(), nowMs = 1_000)
        assertEquals(UnlockOutcome.LOCKOUT_STARTED, decision.outcome)
        assertEquals(0, decision.state.failures)
        assertEquals(1, decision.state.lockouts)
        assertEquals(1_000 + UnlockPolicy.BASE_LOCKOUT_MS, decision.state.lockedUntilMs)
    }

    @Test
    fun `a submission during a lockout is refused and the state stays as it was`() {
        val now = 5_000L
        val state = UnlockState(lockouts = 1, lockedUntilMs = now + UnlockPolicy.BASE_LOCKOUT_MS)
        assertTrue(UnlockPolicy.lockedOut(state, now))

        val right = UnlockPolicy.attempt(code, code, state, now)
        assertEquals(UnlockOutcome.REFUSED_LOCKED_OUT, right.outcome)
        assertEquals(state, right.state)

        val missed = UnlockPolicy.attempt(wrong, code, state, state.lockedUntilMs - 1)
        assertEquals(UnlockOutcome.REFUSED_LOCKED_OUT, missed.outcome)
        assertEquals(state, missed.state)
        assertTrue(UnlockPolicy.lockedOut(state, state.lockedUntilMs - 1))
        assertFalse(UnlockPolicy.lockedOut(state, state.lockedUntilMs))
    }

    @Test
    fun `after a lockout expires three more wrong codes lock for 30 minutes`() {
        val first = failToLock(UnlockState(), nowMs = 0)
        val expiredAt = first.state.lockedUntilMs
        assertFalse(UnlockPolicy.lockedOut(first.state, expiredAt))

        val second = failToLock(first.state, expiredAt)
        assertEquals(UnlockOutcome.LOCKOUT_STARTED, second.outcome)
        assertEquals(0, second.state.failures)
        assertEquals(2, second.state.lockouts)
        assertEquals(expiredAt + UnlockPolicy.BASE_LOCKOUT_MS * 2, second.state.lockedUntilMs)
    }

    @Test
    fun `the lockout caps at 24 hours once the count is large`() {
        assertCapped(lockouts = 7, expected = UnlockPolicy.MAX_LOCKOUT_MS)
        assertCapped(lockouts = 1000, expected = UnlockPolicy.MAX_LOCKOUT_MS)
        // Shifted by 44, the base overflows a signed Long. The cap has to win before that.
        assertCapped(lockouts = 44, expected = UnlockPolicy.MAX_LOCKOUT_MS)
        assertCapped(lockouts = 6, expected = UnlockPolicy.BASE_LOCKOUT_MS shl 6)
    }

    @Test
    fun `a correct code after a lockout clears the lockout count`() {
        val locked = failToLock(UnlockState(), nowMs = 0)
        val decision = UnlockPolicy.attempt(code, code, locked.state, locked.state.lockedUntilMs)
        assertEquals(UnlockOutcome.UNLOCKED, decision.outcome)
        assertEquals(0, decision.state.lockouts)
        assertEquals(UnlockState(), decision.state)
    }

    @Test
    fun `clear drops failures, lockouts, and a live lockout`() {
        val state = UnlockState(failures = 2, lockouts = 5, lockedUntilMs = 50_000)
        assertEquals(UnlockState(), UnlockPolicy.clear(state))
    }

    @Test
    fun `codeOf accepts six signs and nothing else`() {
        assertEquals(code, UnlockPolicy.codeOf("123451"))
        val parsed = UnlockPolicy.codeOf("123451")!!
        assertEquals(UnlockOutcome.UNLOCKED, UnlockPolicy.attempt(parsed, code, UnlockState(), nowMs = 0).outcome)
        assertNull(UnlockPolicy.codeOf("12345"))
        assertNull(UnlockPolicy.codeOf("1234551"))
        assertNull(UnlockPolicy.codeOf(""))
        assertNull(UnlockPolicy.codeOf("123450"))
        assertNull(UnlockPolicy.codeOf("123456"))
        assertNull(UnlockPolicy.codeOf("12345 "))
        assertNull(UnlockPolicy.codeOf(" 123451"))
        assertNull(UnlockPolicy.codeOf("123451\n"))
        assertNull(UnlockPolicy.codeOf("１２３４５１"))
    }

    @Test
    fun `enabling with no code is refused and does not clear`() {
        val state = UnlockState(failures = 2, lockouts = 1, lockedUntilMs = 9)
        assertNull(UnlockPolicy.applySettings(enabled = true, clearLockout = true, codeSet = false, state))
        assertNull(UnlockPolicy.applySettings(enabled = true, clearLockout = false, codeSet = false, state))
    }

    @Test
    fun `a stored code lets the setting change and a clear drops the lockout`() {
        val state = UnlockState(failures = 2, lockouts = 1, lockedUntilMs = 9)
        assertSame(state, UnlockPolicy.applySettings(enabled = true, clearLockout = false, codeSet = true, state))
        assertSame(state, UnlockPolicy.applySettings(enabled = false, clearLockout = false, codeSet = false, state))
        assertEquals(UnlockState(), UnlockPolicy.applySettings(enabled = true, clearLockout = true, codeSet = true, state))
        assertEquals(UnlockState(), UnlockPolicy.applySettings(enabled = false, clearLockout = true, codeSet = false, state))
    }

    @Test
    fun `a lockout record round-trips and a damaged one reads as none`() {
        val state = UnlockState(failures = 2, lockouts = 4, lockedUntilMs = 1_700_000_000_000)
        assertEquals(state, UnlockPolicy.decodeState(UnlockPolicy.encodeState(state)))
        assertEquals(UnlockState(), UnlockPolicy.decodeState(ByteArray(0)))
        assertEquals(UnlockState(), UnlockPolicy.decodeState("nope".encodeToByteArray()))
        assertEquals(UnlockState(), UnlockPolicy.decodeState("1\n2\n".encodeToByteArray()))
        assertEquals(UnlockState(), UnlockPolicy.decodeState("-1\n0\n0\n".encodeToByteArray()))
        assertEquals(UnlockState(), UnlockPolicy.decodeState("1\n-2\n3\n".encodeToByteArray()))
        assertEquals(UnlockState(), UnlockPolicy.decodeState("1\n2\n-3\n".encodeToByteArray()))
    }

    @Test
    fun `validCode rejects the wrong length and a sign of 0 or 6`() {
        assertTrue(UnlockPolicy.validCode(listOf(1, 2, 3, 4, 5, 5)))
        assertFalse(UnlockPolicy.validCode(listOf(1, 2, 3, 4, 5)))
        assertFalse(UnlockPolicy.validCode(listOf(1, 2, 3, 4, 5, 5, 5)))
        assertFalse(UnlockPolicy.validCode(emptyList()))
        assertFalse(UnlockPolicy.validCode(listOf(0, 1, 2, 3, 4, 5)))
        assertFalse(UnlockPolicy.validCode(listOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `a sign that only matches in its low byte is still wrong`() {
        val aliased = listOf(1, 2, 3, 4, 5, 1 + 256)
        val decision = UnlockPolicy.attempt(aliased, code, UnlockState(), nowMs = 0)
        assertEquals(UnlockOutcome.WRONG_CODE, decision.outcome)
        assertEquals(1, decision.state.failures)
    }

    private fun failToLock(start: UnlockState, nowMs: Long): UnlockDecision {
        var state = start
        repeat(UnlockPolicy.MAX_FAILURES - 1) { index ->
            val decision = UnlockPolicy.attempt(wrong, code, state, nowMs)
            assertEquals(UnlockOutcome.WRONG_CODE, decision.outcome)
            assertEquals(index + 1, decision.state.failures)
            assertEquals(start.lockouts, decision.state.lockouts)
            assertEquals(start.lockedUntilMs, decision.state.lockedUntilMs)
            state = decision.state
        }
        return UnlockPolicy.attempt(wrong, code, state, nowMs)
    }

    private fun assertCapped(lockouts: Int, expected: Long) {
        val now = 1_000L
        val state = UnlockState(failures = UnlockPolicy.MAX_FAILURES - 1, lockouts = lockouts)
        val decision = UnlockPolicy.attempt(wrong, code, state, now)
        assertEquals(UnlockOutcome.LOCKOUT_STARTED, decision.outcome)
        assertEquals(0, decision.state.failures)
        assertEquals(lockouts + 1, decision.state.lockouts)
        assertEquals(now + expected, decision.state.lockedUntilMs)
    }
}
