package com.homecam.core.lens

/** What one camera stream did during a trial: its frame rate in each [ConcurrencyVerdict.WINDOW_SECONDS] window. */
data class StreamTrial(
    val cameraId: String,
    val physicalId: String?,
    val windowFps: List<Float>,
    val error: String?,
)

/** One attempt to run every stream of a set at once, at one size, for [seconds]. */
data class Trial(
    val width: Int,
    val height: Int,
    val seconds: Int,
    val ranMs: Long,
    val streams: List<StreamTrial>,
    val tempRiseC: Float?,
    val openError: String?,
)

/**
 * A set of cameras is feasible when every stream holds [MIN_FPS] through the whole trial
 * with no camera error. What a HAL lists as concurrent is a claim; the trial is the
 * evidence, and a claim with no passing trial counts as infeasible.
 */
object ConcurrencyVerdict {

    const val MIN_FPS = 24f
    const val WINDOW_SECONDS = 10

    /** A trial cut short by more than this fraction did not see the whole interval. */
    private const val MIN_RAN_FRACTION = 0.95

    fun feasible(trial: Trial): Boolean {
        if (trial.openError != null || trial.streams.isEmpty()) return false
        if (trial.ranMs < trial.seconds * 1000L * MIN_RAN_FRACTION) return false
        val windows = trial.seconds / WINDOW_SECONDS
        return trial.streams.all { stream ->
            stream.error == null && stream.windowFps.size >= windows && stream.windowFps.all { it >= MIN_FPS }
        }
    }

    /** The largest size at which the set passed, or null if it never did. */
    fun best(trials: List<Trial>): Trial? =
        trials.filter(::feasible).maxByOrNull { it.width.toLong() * it.height }
}
