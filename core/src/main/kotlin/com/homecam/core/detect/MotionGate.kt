package com.homecam.core.detect

/**
 * The cheap half of the detection pipeline (§6). Running the neural detector on every
 * frame is the fastest way to cook a 2020 phone, so it only ever sees frames that already
 * showed movement.
 *
 * Frames are compared as coarse luma grids: differencing at full resolution would cost
 * more than it saves, and a person walking past moves whole cells, not pixels.
 */
class MotionGate(
    private val cellDelta: Int = DEFAULT_CELL_DELTA,
    private val minChangedFraction: Float = DEFAULT_MIN_CHANGED_FRACTION,
    /**
     * Once triggered the gate stays open briefly. Motion is not continuous frame to frame
     * — somebody standing still between steps would otherwise close it mid-event and
     * split one visit into several.
     */
    private val holdMs: Long = DEFAULT_HOLD_MS,
) {
    private var previous: IntArray? = null
    private var openUntilMs = 0L

    /** True when [grid] should be handed to the detector. */
    fun accept(grid: IntArray, nowMs: Long): Boolean {
        val last = previous
        previous = grid.copyOf()

        if (last == null || last.size != grid.size) return false

        var changed = 0
        for (i in grid.indices) {
            if (kotlin.math.abs(grid[i] - last[i]) >= cellDelta) changed++
        }
        val fraction = changed.toFloat() / grid.size

        if (fraction >= minChangedFraction) openUntilMs = nowMs + holdMs
        return nowMs < openUntilMs
    }

    fun reset() {
        previous = null
        openUntilMs = 0
    }

    val open: Boolean get() = openUntilMs > 0

    private companion object {
        const val DEFAULT_CELL_DELTA = 12
        const val DEFAULT_MIN_CHANGED_FRACTION = 0.02f
        const val DEFAULT_HOLD_MS = 3_000L
    }
}
