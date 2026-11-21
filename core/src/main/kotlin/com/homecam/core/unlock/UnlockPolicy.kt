package com.homecam.core.unlock

import com.homecam.core.crypto.ConstantTime

/**
 * Failures and lockouts the camera keeps across a restart. Only a correct code
 * or [UnlockPolicy.clear] zeroes it; an expired lockout does not.
 */
data class UnlockState(
    val failures: Int = 0,
    val lockouts: Int = 0,
    val lockedUntilMs: Long = 0,
)

enum class UnlockOutcome {
    UNLOCKED,
    WRONG_CODE,
    LOCKOUT_STARTED,
    REFUSED_LOCKED_OUT,
}

data class UnlockDecision(
    val outcome: UnlockOutcome,
    val state: UnlockState,
)

/**
 * Gesture door code. Three misses lock for [BASE_LOCKOUT_MS], and each further
 * lockout doubles that, up to [MAX_LOCKOUT_MS]. The lockout count is what doubles,
 * so it has to survive the timer running out.
 */
object UnlockPolicy {
    const val MAX_FAILURES = 3
    const val BASE_LOCKOUT_MS = 15 * 60 * 1000L
    const val MAX_LOCKOUT_MS = 24 * 60 * 60 * 1000L
    const val CODE_LENGTH = 6

    fun attempt(signs: List<Int>, code: List<Int>, state: UnlockState, nowMs: Long): UnlockDecision {
        if (lockedOut(state, nowMs)) return UnlockDecision(UnlockOutcome.REFUSED_LOCKED_OUT, state)
        if (ConstantTime.equals(bytes(signs), bytes(code))) {
            return UnlockDecision(UnlockOutcome.UNLOCKED, UnlockState())
        }
        val failures = state.failures + 1
        if (failures >= MAX_FAILURES) {
            val lockouts = state.lockouts + 1
            return UnlockDecision(
                UnlockOutcome.LOCKOUT_STARTED,
                UnlockState(failures = 0, lockouts = lockouts, lockedUntilMs = nowMs + durationMs(lockouts)),
            )
        }
        return UnlockDecision(UnlockOutcome.WRONG_CODE, state.copy(failures = failures))
    }

    fun clear(state: UnlockState): UnlockState = UnlockState()

    fun lockedOut(state: UnlockState, nowMs: Long): Boolean = nowMs < state.lockedUntilMs

    fun validCode(signs: List<Int>): Boolean = signs.size == CODE_LENGTH && signs.all { it in 1..5 }

    /** Four bytes per sign, so 257 cannot pass for 1 the way a single toByte() would. */
    private fun bytes(values: List<Int>): ByteArray {
        val out = ByteArray(values.size * 4)
        for (i in values.indices) {
            val value = values[i]
            out[i * 4] = (value ushr 24).toByte()
            out[i * 4 + 1] = (value ushr 16).toByte()
            out[i * 4 + 2] = (value ushr 8).toByte()
            out[i * 4 + 3] = value.toByte()
        }
        return out
    }

    private fun durationMs(lockouts: Int): Long {
        val exponent = (lockouts - 1).coerceAtLeast(0)
        var duration = BASE_LOCKOUT_MS
        repeat(exponent) {
            // Cap before the shift. A Long shift uses only the low 6 bits of the
            // count, so a large lockouts value wraps and the lockout can come out negative.
            if (duration > MAX_LOCKOUT_MS / 2) return MAX_LOCKOUT_MS
            duration = duration shl 1
        }
        return minOf(duration, MAX_LOCKOUT_MS)
    }
}
