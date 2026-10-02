package com.homecam.core.health

import com.homecam.proto.Capabilities
import com.homecam.proto.RecordingMode
import com.homecam.proto.RuntimeStatus
import kotlin.math.roundToInt

/** What the camera is recording and why, as lines a person can read. */
data class RecordingReadout(val title: String, val details: List<String>)

data class HealthItem(val label: String, val value: String)

object HealthReadout {

    fun recording(status: RuntimeStatus, formatTime: (Long) -> String): RecordingReadout {
        val asked = status.requestedRecordingMode
        val details = mutableListOf<String>()
        if (status.effectiveRecordingMode != asked) {
            val effect = when (status.cap) {
                RuntimeStatus.Cap.CAP_NO_RECORDING -> "nothing is being kept"
                else -> "recording detections only"
            }
            details += "Paused: ${causeOf(status.capReason)}, $effect since ${formatTime(status.capSinceMs)}. " +
                "Resumes automatically."
        }
        if (status.recordingRevertAtMs > 0) {
            details += "Reverts to ${modeName(status.recordingRevertTo)} at ${formatTime(status.recordingRevertAtMs)}."
        }
        return RecordingReadout(modeName(asked), details)
    }

    fun card(
        status: RuntimeStatus,
        capabilities: Capabilities?,
        pausePercent: Int,
    ): List<HealthItem> {
        val items = mutableListOf<HealthItem>()
        if (status.batteryPercent >= 0) items += HealthItem("Battery", battery(status, pausePercent))
        items += HealthItem("Heat", heat(status, capabilities))
        if (status.freeBytes > 0) items += HealthItem("Storage", storage(status))
        if (status.cap != RuntimeStatus.Cap.CAP_NONE) {
            items += HealthItem("Protection", "Paused: ${causeOf(status.capReason)}")
        }
        return items
    }

    private fun battery(status: RuntimeStatus, pausePercent: Int): String {
        val percent = "%.0f%%".format(status.batteryPercent)
        val drain = status.drainPercentPerHour
        return when {
            status.charging && drain > NET_DISCHARGE_NOTE -> "$percent, charger losing ${"%.0f".format(drain)}%/h"
            status.charging -> "$percent, charging"
            drain > MIN_DRAIN_SHOWN -> {
                val hours = (status.batteryPercent - pausePercent) / drain
                val estimate = if (hours > 0 && hours < MAX_ESTIMATE_HOURS) ", ${formatHours(hours)} to $pausePercent%" else ""
                "$percent, draining ${"%.1f".format(drain)}%/h$estimate"
            }
            else -> percent
        }
    }

    private fun heat(status: RuntimeStatus, capabilities: Capabilities?): String {
        val temp = "%.1f °C".format(status.batteryTempC)
        val supported = capabilities?.thermalStatusSupported ?: (status.thermalStatus != RuntimeStatus.Thermal.THERMAL_UNKNOWN)
        if (!supported) return temp
        val word = when (status.thermalStatus) {
            RuntimeStatus.Thermal.THERMAL_NONE, RuntimeStatus.Thermal.THERMAL_LIGHT -> "Cool"
            RuntimeStatus.Thermal.THERMAL_MODERATE -> "Warm"
            RuntimeStatus.Thermal.THERMAL_SEVERE -> "Hot"
            RuntimeStatus.Thermal.THERMAL_CRITICAL, RuntimeStatus.Thermal.THERMAL_EMERGENCY,
            RuntimeStatus.Thermal.THERMAL_SHUTDOWN -> "Critical"
            else -> return temp
        }
        return "$word, $temp"
    }

    private fun storage(status: RuntimeStatus): String {
        val free = formatBytes(status.freeBytes)
        return when {
            status.floorBytes > 0 && !status.floorHeld -> "$free free, below the floor"
            else -> "$free free"
        }
    }

    private fun causeOf(reason: RuntimeStatus.CapReason): String = HealthProtocol.describeCause(
        when (reason) {
            RuntimeStatus.CapReason.REASON_THERMAL -> Axis.THERMAL
            RuntimeStatus.CapReason.REASON_STORAGE -> Axis.STORAGE
            RuntimeStatus.CapReason.REASON_BATTERY -> Axis.BATTERY
            else -> null
        },
    )

    private fun modeName(mode: RecordingMode): String = when (mode) {
        RecordingMode.CONTINUOUS -> "Continuous"
        RecordingMode.OFF -> "Off"
        else -> "Detection"
    }

    private fun formatHours(hours: Float): String = when {
        hours < 1f -> "${(hours * 60).roundToInt()} min"
        else -> "%.1f h".format(hours)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= GB -> "%.0f GB".format(bytes / GB.toDouble())
        else -> "%.0f MB".format(bytes / 1_000_000.0)
    }

    private const val GB = 1_000_000_000L
    private const val MIN_DRAIN_SHOWN = 0.5f
    private const val NET_DISCHARGE_NOTE = 0.5f
    private const val MAX_ESTIMATE_HOURS = 48f
}
