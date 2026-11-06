package com.homecam.core.media

object AutoRotation {
    const val UNKNOWN = -1
    const val HOLD_MS = 1_000L
    private const val BAND_DEGREES = 30

    /** [deviceQuarterDegrees] is the device quarter (0 natural, 90 left-side-up). Front frames are mirrored, so the sign flips. */
    fun extraDegrees(deviceQuarterDegrees: Int, front: Boolean): Int {
        val turned = quarter(deviceQuarterDegrees)
        return if (front) quarter(-turned) else turned
    }

    fun quarter(degrees: Int): Int {
        val wrapped = wrap(degrees)
        return wrap(((wrapped + 45) / 90) * 90)
    }

    class Hysteresis(initial: Int? = null) {
        private var settled: Int? = initial?.let { quarter(it) }
        private var candidate: Int? = null
        private var candidateSinceMs: Long? = null

        val pending: Boolean get() = candidate != null

        fun update(angleDegrees: Int, atMs: Long): Int? {
            if (angleDegrees < 0) {
                clearCandidate()
                return settled
            }
            val target = withinBand(angleDegrees)
            if (target == null || target == settled) {
                clearCandidate()
                return settled
            }
            if (candidate != target) {
                candidate = target
                candidateSinceMs = atMs
                return settled
            }
            val since = candidateSinceMs ?: atMs
            if (atMs - since >= HOLD_MS) {
                settled = target
                clearCandidate()
            }
            return settled
        }

        private fun clearCandidate() {
            candidate = null
            candidateSinceMs = null
        }

        private fun withinBand(angleDegrees: Int): Int? {
            val wrapped = wrap(angleDegrees)
            val nearest = quarter(wrapped)
            return if (distance(wrapped, nearest) <= BAND_DEGREES) nearest else null
        }
    }

    private fun wrap(degrees: Int): Int = ((degrees % 360) + 360) % 360

    private fun distance(a: Int, b: Int): Int {
        val delta = wrap(a - b)
        return minOf(delta, 360 - delta)
    }
}
