package com.homecam.core.health

import com.homecam.proto.RecordingMode
import com.homecam.proto.RecordingModeSetting
import com.homecam.proto.RecordingRevert

/**
 * A timed recording mode is the camera's own promise, because the viewer that asked may be
 * on a plane when the hour is up. Protection caps are independent of it: a cap neither
 * extends nor cancels the timer.
 */
object RecordingTimer {
    const val MAX_MINUTES = 24 * 60

    /** The mode to store, and the revert to schedule alongside it, if the request asked for one. */
    fun applyRequest(request: RecordingModeSetting, nowMs: Long): Pair<RecordingMode, RecordingRevert?> {
        val minutes = request.revertAfterMinutes.coerceAtMost(MAX_MINUTES)
        if (minutes <= 0 || request.revertTo == request.mode) return request.mode to null
        val revert = RecordingRevert.newBuilder()
            .setAtMs(nowMs + minutes * 60_000L)
            .setTo(request.revertTo)
            .build()
        return request.mode to revert
    }

    /** The mode to return to now, or null while the timer is absent or still running. */
    fun dueMode(revert: RecordingRevert?, nowMs: Long): RecordingMode? =
        revert?.takeIf { it.atMs > 0 && nowMs >= it.atMs }?.to
}
