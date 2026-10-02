package com.homecam.core.health

import com.homecam.proto.RecordingMode

/** Whether the recorder should have a muxer open right now. */
object RecordingGate {

    fun shouldKeepRecording(
        mode: RecordingMode,
        cap: Cap,
        armed: Boolean,
        nowMs: Long,
        manualUntilMs: Long,
        detectionUntilMs: Long,
    ): Boolean = when {
        cap != Cap.NO_RECORDING && manualUntilMs > nowMs -> true
        else -> when (HealthProtocol.effectiveMode(mode, cap)) {
            RecordingMode.CONTINUOUS -> true
            RecordingMode.DETECTION -> armed && detectionUntilMs > nowMs
            else -> false
        }
    }
}
