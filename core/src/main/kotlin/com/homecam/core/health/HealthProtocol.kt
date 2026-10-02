package com.homecam.core.health

import com.homecam.proto.HardwareProtection
import com.homecam.proto.RecordingMode
import com.homecam.proto.RuntimeStatus
import com.homecam.proto.SettingsSnapshot

object HealthProtocol {

    /** The mode in force: what was asked for, held down by whatever the cap forbids. */
    fun effectiveMode(requested: RecordingMode, cap: Cap): RecordingMode = when (cap) {
        Cap.NONE -> requested
        Cap.NO_CONTINUOUS ->
            if (requested == RecordingMode.CONTINUOUS) RecordingMode.DETECTION else requested
        Cap.NO_RECORDING -> RecordingMode.OFF
    }

    /** The cause as a clause that reads after "because" or "paused:"; the viewer shows it verbatim. */
    fun describeCause(reason: Axis?): String = when (reason) {
        Axis.THERMAL -> "the phone is too hot"
        Axis.BATTERY -> "the battery is low or draining"
        Axis.STORAGE -> "the phone is nearly out of storage"
        null -> "the camera is protecting itself"
    }

    /** A snapshot that never had the setting written reads as the defaults, not as everything off. */
    fun configOf(settings: SettingsSnapshot): HealthConfig {
        if (!settings.hasHardwareProtection()) return HealthConfig()
        val protection = settings.hardwareProtection
        val percent = protection.batteryPausePercent.takeIf { it > 0 } ?: HealthConfig.DEFAULT_BATTERY_PAUSE_PERCENT
        return HealthConfig(
            thermalAutoPause = protection.thermalAutoPause,
            batteryAutoPause = protection.batteryAutoPause,
            batteryPausePercent = HealthConfig.clampBatteryPausePercent(percent),
        )
    }

    fun protectionOf(config: HealthConfig): HardwareProtection = HardwareProtection.newBuilder()
        .setThermalAutoPause(config.thermalAutoPause)
        .setBatteryAutoPause(config.batteryAutoPause)
        .setBatteryPausePercent(HealthConfig.clampBatteryPausePercent(config.batteryPausePercent))
        .build()

    fun RuntimeStatus.Builder.setHealth(state: HealthState, requested: RecordingMode): RuntimeStatus.Builder =
        setThermalLevel(state.thermal.level.toProto())
            .setBatteryLevel(state.battery.level.toProto())
            .setStorageLevel(state.storage.level.toProto())
            .setCap(state.cap.toProto())
            .setCapReason(state.capReason.toProto())
            .setCapSinceMs(state.capSinceMs)
            .setDrainPercentPerHour(state.drainPercentPerHour)
            .setRequestedRecordingMode(requested)
            .setEffectiveRecordingMode(effectiveMode(requested, state.cap))

    fun ThermalStatus?.toProto(): RuntimeStatus.Thermal = when (this) {
        null -> RuntimeStatus.Thermal.THERMAL_UNKNOWN
        ThermalStatus.NONE -> RuntimeStatus.Thermal.THERMAL_NONE
        ThermalStatus.LIGHT -> RuntimeStatus.Thermal.THERMAL_LIGHT
        ThermalStatus.MODERATE -> RuntimeStatus.Thermal.THERMAL_MODERATE
        ThermalStatus.SEVERE -> RuntimeStatus.Thermal.THERMAL_SEVERE
        ThermalStatus.CRITICAL -> RuntimeStatus.Thermal.THERMAL_CRITICAL
        ThermalStatus.EMERGENCY -> RuntimeStatus.Thermal.THERMAL_EMERGENCY
        ThermalStatus.SHUTDOWN -> RuntimeStatus.Thermal.THERMAL_SHUTDOWN
    }

    fun Level.toProto(): RuntimeStatus.Level = when (this) {
        Level.OK -> RuntimeStatus.Level.LEVEL_OK
        Level.WARN -> RuntimeStatus.Level.LEVEL_WARN
        Level.PAUSE -> RuntimeStatus.Level.LEVEL_PAUSE
        Level.CRITICAL -> RuntimeStatus.Level.LEVEL_CRITICAL
    }

    fun Cap.toProto(): RuntimeStatus.Cap = when (this) {
        Cap.NONE -> RuntimeStatus.Cap.CAP_NONE
        Cap.NO_CONTINUOUS -> RuntimeStatus.Cap.CAP_NO_CONTINUOUS
        Cap.NO_RECORDING -> RuntimeStatus.Cap.CAP_NO_RECORDING
    }

    fun Axis?.toProto(): RuntimeStatus.CapReason = when (this) {
        null -> RuntimeStatus.CapReason.REASON_NONE
        Axis.THERMAL -> RuntimeStatus.CapReason.REASON_THERMAL
        Axis.STORAGE -> RuntimeStatus.CapReason.REASON_STORAGE
        Axis.BATTERY -> RuntimeStatus.CapReason.REASON_BATTERY
    }
}
