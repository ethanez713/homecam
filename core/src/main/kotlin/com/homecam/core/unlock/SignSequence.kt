package com.homecam.core.unlock

/**
 * Turns per-frame finger counts into a door code.
 *
 * Idle waits for a fist. That opens entry, and each sign is then one to five
 * fingers. A fist has to come between signs, which is what lets a digit
 * repeat; the sign that fills [length] submits and entry closes. A count is
 * taken only once the same value has lasted [holdMs] from its first frame and
 * been seen on two frames. One frame with no count keeps the run, two end it,
 * and a run is taken at most once. [idleResetMs] after the last open or
 * accepted sign, an entry still open is dropped.
 *
 * The signs are the door code, so [Event.Submitted] does not print them.
 */
class SignSequence(
    private val holdMs: Long = 400,
    private val idleResetMs: Long = 8_000,
    private val length: Int = 6,
) {
    private var phase = Phase.IDLE
    private var anchorMs: Long? = null
    private val signs = mutableListOf<Int>()

    private var lastAtMs: Long? = null
    private var runCount: Int? = null
    private var runStartMs = 0L
    private var runFrames = 0
    private var nullStreak = 0
    private var runAccepted = false

    fun feed(atMs: Long, count: Int?): Event? {
        val previous = lastAtMs
        if (previous != null && atMs < previous) return null
        lastAtMs = atMs

        // The frame that crosses the idle gap is dropped, not read as a sign.
        if (idleElapsed(atMs)) {
            returnToIdle()
            return Event.Reset
        }

        val held = heldCount(atMs, count) ?: return null
        return take(atMs, held)
    }

    private fun idleElapsed(atMs: Long): Boolean {
        if (phase == Phase.IDLE) return false
        val since = anchorMs ?: return false
        return atMs - since >= idleResetMs
    }

    private fun heldCount(atMs: Long, count: Int?): Int? {
        if (count == null) {
            if (runCount == null) return null
            nullStreak += 1
            if (nullStreak >= 2) clearRun()
            return null
        }
        if (count != runCount) {
            startRun(count, atMs)
            return null
        }
        nullStreak = 0
        runFrames += 1
        if (runAccepted || runFrames < 2 || atMs - runStartMs < holdMs) return null
        runAccepted = true
        return count
    }

    private fun take(atMs: Long, count: Int): Event? = when (phase) {
        Phase.IDLE -> {
            if (count != FIST) {
                null
            } else {
                phase = Phase.OPEN
                anchorMs = atMs
                Event.Opened
            }
        }
        Phase.OPEN -> {
            if (count !in 1..5) {
                null
            } else {
                signs += count
                if (signs.size >= length) {
                    val submitted = Event.Submitted(signs.toList())
                    returnToIdle()
                    submitted
                } else {
                    phase = Phase.AWAIT_FIST
                    anchorMs = atMs
                    Event.SignAccepted(signs.lastIndex)
                }
            }
        }
        Phase.AWAIT_FIST -> {
            if (count != FIST) {
                null
            } else {
                phase = Phase.OPEN
                null
            }
        }
    }

    private fun startRun(count: Int, atMs: Long) {
        runCount = count
        runStartMs = atMs
        runFrames = 1
        nullStreak = 0
        runAccepted = false
    }

    private fun returnToIdle() {
        phase = Phase.IDLE
        anchorMs = null
        signs.clear()
        clearRun()
    }

    private fun clearRun() {
        runCount = null
        runFrames = 0
        nullStreak = 0
        runAccepted = false
    }

    private enum class Phase { IDLE, OPEN, AWAIT_FIST }

    sealed interface Event {
        data object Opened : Event
        data class SignAccepted(val index: Int) : Event
        data object Reset : Event

        data class Submitted(val signs: List<Int>) : Event {
            // The default data-class text would print the door code.
            override fun toString() = "Submitted(${signs.size} signs)"
        }
    }

    private companion object {
        const val FIST = 0
    }
}
