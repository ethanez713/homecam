package com.homecam.app.camera

import android.content.Context
import android.util.Log
import com.homecam.core.diagnostics.DiagnosticsLines
import com.homecam.core.diagnostics.DiagnosticsLog
import com.homecam.core.health.Axis
import com.homecam.core.health.HealthSample
import com.homecam.core.health.HealthState
import com.homecam.core.health.Level
import com.homecam.core.retention.PrunePlan
import com.homecam.proto.RecordingMode
import com.homecam.proto.RuntimeStatus
import org.json.JSONObject
import java.io.File

/**
 * A persistent, restart-surviving diagnostics log for the camera role, written for the
 * device gates — above all GATE-C's 24-hour soak. logcat is a ring buffer that wraps long
 * before a day is out and does not survive the process being killed, which is precisely the
 * failure GATE-C exists to catch; so the events that would explain a soak failure are
 * appended as JSON lines to app-private storage instead, where they outlive the run.
 *
 * Each line carries a per-process [run] id, so two `service_start` lines with different run
 * ids inside the soak window means the process was killed and restarted — and the
 * `prev_run_unclean` flag on a start says so outright, since a clean stop writes a
 * `service_stop` and a kill does not.
 *
 * Tier 0 by the effectiveness-metrics rule: one wide event per interesting occurrence, shape
 * not content, capped so it cannot grow without bound. Nothing here is read at runtime; it is
 * pulled and inspected by hand after a gate run (see docs/DECISIONS.md).
 */
class CameraDiagnostics(context: Context, directory: File = File(context.filesDir, "diagnostics")) {

    private val dir = directory.apply { mkdirs() }
    private val file = File(dir, "camera.jsonl")
    private val log = DiagnosticsLog(file) { Log.w(TAG, "diagnostics write failed", it) }

    val run: String = DiagnosticsLines.newRunId()

    fun start(reason: String) = event("service_start") {
        put("reason", reason)
        put("prev_run_unclean", lastRunWasKilled())
    }

    fun stop(reason: String) = event("service_stop") { put("reason", reason) }

    /** [gapMs] is the wall-clock gap since the previous segment ended — a large one is a hole in the record. */
    fun segment(id: String, durationMs: Int, sizeBytes: Long, gapMs: Long) = event("segment") {
        put("id", id)
        put("duration_ms", durationMs)
        put("size_bytes", sizeBytes)
        put("gap_ms", gapMs)
    }

    fun prune(plan: PrunePlan) = event("prune") {
        put("deleted", plan.delete.size)
        put("freed_bytes", plan.delete.sumOf { it.sizeBytes })
        put("free_after_bytes", plan.freeBytesAfter)
        put("floor_bytes", plan.minFreeBytes)
        put("floor_held", plan.freeFloorHeld)
        put("pinned_bytes", plan.pinnedBytes)
        put("pinned_near_ceiling", plan.pinnedNearCeiling)
    }

    fun heartbeat(status: RuntimeStatus, recording: Boolean, mode: String) = event("heartbeat") {
        put("uptime_ms", status.uptimeMs)
        put("free_bytes", status.freeBytes)
        put("clip_bytes", status.clipBytes)
        put("battery_pct", status.batteryPercent)
        put("battery_temp_c", status.batteryTempC)
        put("charging", status.charging)
        put("thermal_throttled", status.thermalThrottled)
        put("capture_fps", status.captureFps)
        put("detector_fps", status.detectorFps)
        put("recording", recording)
        put("mode", mode)
        put("thermal_status", status.thermalStatus.name.removePrefix("THERMAL_").lowercase())
        if (!status.thermalHeadroom.isNaN()) put("thermal_headroom", status.thermalHeadroom)
        put("drain_pct_per_h", status.drainPercentPerHour)
        put("cap", status.cap.name.removePrefix("CAP_").lowercase())
        put("effective_mode", status.effectiveRecordingMode.name)
    }

    /** One line per axis that changed level, with the reading that moved it. */
    fun healthState(axis: Axis, from: Level, to: Level, reading: HealthSample, state: HealthState) =
        event("health_state") {
            put("axis", axis.name.lowercase())
            put("from", from.name.lowercase())
            put("to", to.name.lowercase())
            put("battery_pct", reading.batteryPercent)
            put("battery_temp_c", reading.batteryTempC)
            put("charging", reading.plugged)
            put("thermal_status", reading.thermalStatus?.name?.lowercase() ?: "unknown")
            put("drain_pct_per_h", state.drainPercentPerHour)
            put("free_bytes", reading.freeBytes)
            put("floor_bytes", reading.floorBytes)
            put("floor_held", reading.floorHeld)
        }

    fun recordingReverted(from: RecordingMode, to: RecordingMode, dueAtMs: Long) = event("recording_revert") {
        put("from", from.name)
        put("to", to.name)
        put("due_at_ms", dueAtMs)
        put("late_ms", System.currentTimeMillis() - dueAtMs)
    }

    fun recordingCap(state: HealthState, requested: RecordingMode, effective: RecordingMode) =
        event("recording_cap") {
            put("cap", state.cap.name.lowercase())
            put("reason", state.capReason?.name?.lowercase() ?: "none")
            put("requested_mode", requested.name)
            put("effective_mode", effective.name)
        }

    fun soundEvent(category: String, levelDb: Float) = event("sound_event") {
        put("category", category)
        put("level_db", levelDb)
    }

    fun siren(on: Boolean, seconds: Int) = event("siren") {
        put("on", on)
        put("seconds", seconds)
    }

    fun alert(kind: String, recipients: Int, delivered: Int) = event("alert") {
        put("kind", kind)
        put("recipients", recipients)
        put("delivered", delivered)
    }

    fun error(where: String, message: String?) = event("error") {
        put("where", where)
        put("message", message ?: "")
    }

    /** [errorCode] is the last CameraX camera-state error, when the camera has reported one. */
    fun cameraBlind(errorCode: Int?, errorName: String?) = event("camera_blind") {
        if (errorCode != null) put("camera_error", errorCode)
        if (errorName != null) put("camera_error_name", errorName)
    }

    fun cameraRecovered() = event("camera_recovered") {}

    private inline fun event(type: String, build: JSONObject.() -> Unit) {
        val line = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("run", run)
            .put("event", type)
            .apply(build)
            .toString()
        log.append(line)
    }

    /** The previous run ended in a kill if the last line written was anything but a clean stop. */
    private fun lastRunWasKilled(): Boolean = runCatching {
        if (!file.exists()) return false
        val last = file.useLines { lines -> lines.lastOrNull { it.isNotBlank() } } ?: return false
        JSONObject(last).optString("event") != "service_stop"
    }.getOrDefault(false)

    private companion object {
        const val TAG = "CameraDiagnostics"
    }
}
