package com.homecam.core.health

enum class Level { OK, WARN, PAUSE, CRITICAL }

enum class Axis { THERMAL, STORAGE, BATTERY }

/** What protection allows the recorder to do, on top of the mode the user asked for. */
enum class Cap { NONE, NO_CONTINUOUS, NO_RECORDING }

/** Mirrors `PowerManager.THERMAL_STATUS_*`, so the value is the platform's own integer. */
enum class ThermalStatus {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN;

    companion object {
        fun fromPlatform(value: Int): ThermalStatus = entries[value.coerceIn(0, entries.size - 1)]
    }
}

data class HealthConfig(
    val thermalAutoPause: Boolean = true,
    val batteryAutoPause: Boolean = true,
    val batteryPausePercent: Int = DEFAULT_BATTERY_PAUSE_PERCENT,
) {
    companion object {
        const val DEFAULT_BATTERY_PAUSE_PERCENT = 30
        const val MIN_BATTERY_PAUSE_PERCENT = 10
        const val MAX_BATTERY_PAUSE_PERCENT = 80

        fun clampBatteryPausePercent(percent: Int): Int =
            percent.coerceIn(MIN_BATTERY_PAUSE_PERCENT, MAX_BATTERY_PAUSE_PERCENT)
    }
}

/** Every threshold the policy acts on; GATE-H retunes them here and nowhere else. */
object HealthThresholds {
    val THERMAL_WARN = ThermalStatus.MODERATE
    val THERMAL_PAUSE = ThermalStatus.SEVERE
    val THERMAL_CRITICAL = ThermalStatus.CRITICAL

    const val TEMP_WARN_C = 40f
    const val TEMP_PAUSE_C = 45f
    const val TEMP_CRITICAL_C = 50f

    const val BATTERY_WARN_PERCENT = 40
    const val BATTERY_PLUGGED_PAUSE_PERCENT = 50
    const val BATTERY_RELEASE_MARGIN_PERCENT = 10
    const val DRAIN_PAUSE_PERCENT_PER_HOUR = 8f

    /** A plugged phone whose charge falls faster than this is losing to the app. */
    const val NET_DISCHARGE_EPSILON_PERCENT_PER_HOUR = 0.5f

    const val RATE_WINDOW_MS = 30 * 60_000L
    const val MIN_SPAN_PERCENT_ONLY_MS = 20 * 60_000L
    const val MIN_SPAN_WITH_COUNTER_MS = 5 * 60_000L
    const val MIN_RATE_SAMPLES = 3

    const val RELEASE_DWELL_MS = 10 * 60_000L

    const val STORAGE_WARN_DAYS = 1f
    const val STORAGE_CRITICAL_FLOOR_FRACTION = 0.5
}

data class HealthSample(
    val atMs: Long,
    val batteryPercent: Int,
    /** `BATTERY_PROPERTY_CHARGE_COUNTER` in µAh; zero or negative when the phone has none. */
    val chargeCounterUah: Long,
    val plugged: Boolean,
    val batteryTempC: Float,
    /** Null below API 29, where only [batteryTempC] speaks for heat. */
    val thermalStatus: ThermalStatus?,
    val freeBytes: Long,
    val floorBytes: Long,
    val floorHeld: Boolean,
    val pinnedNearCeiling: Boolean,
    /** Days of recording the free space holds at the current rate; null when unknown. */
    val storageProjectionDays: Float?,
)

data class AxisState(val level: Level, val sinceMs: Long)

data class HealthState(
    val thermal: AxisState,
    val storage: AxisState,
    val battery: AxisState,
    val cap: Cap,
    /** The axis holding the cap; null when [cap] is [Cap.NONE]. */
    val capReason: Axis?,
    val capSinceMs: Long,
    /** Signed charge trend, positive while rising; null until enough samples exist. */
    val netPercentPerHour: Float?,
) {
    val drainPercentPerHour: Float get() = maxOf(0f, -(netPercentPerHour ?: 0f))

    fun level(axis: Axis): Level = when (axis) {
        Axis.THERMAL -> thermal.level
        Axis.STORAGE -> storage.level
        Axis.BATTERY -> battery.level
    }

    companion object {
        val INITIAL = HealthState(
            thermal = AxisState(Level.OK, 0),
            storage = AxisState(Level.OK, 0),
            battery = AxisState(Level.OK, 0),
            cap = Cap.NONE,
            capReason = null,
            capSinceMs = 0,
            netPercentPerHour = null,
        )
    }
}

/**
 * Turns timestamped samples into a [HealthState]. A level rises the moment a reading
 * demands it and falls only after the reading has stayed lower for
 * [HealthThresholds.RELEASE_DWELL_MS], so a value hovering at a threshold makes one
 * transition rather than many. The user's recording mode is never an input or an output:
 * the result is a cap the recorder applies on top of it.
 */
class HealthPolicy(var config: HealthConfig = HealthConfig()) {
    private class Point(val atMs: Long, val percent: Int, val counterUah: Long)

    private val history = ArrayDeque<Point>()
    private var lastPlugged: Boolean? = null
    private var state = HealthState.INITIAL
    private val releasingSince = mutableMapOf<Axis, Long>()

    fun update(sample: HealthSample): HealthState {
        track(sample)
        val net = netRatePercentPerHour()

        val thermal = step(Axis.THERMAL, state.thermal, thermalRaw(sample), sample.atMs)
        val storage = step(Axis.STORAGE, state.storage, storageRaw(sample), sample.atMs)
        val battery = step(Axis.BATTERY, state.battery, batteryRaw(sample, net), sample.atMs)

        val (cap, reason) = capOf(thermal.level, storage.level, battery.level)
        val capSince = if (cap == state.cap) state.capSinceMs else sample.atMs
        state = HealthState(thermal, storage, battery, cap, reason, capSince, net)
        return state
    }

    private fun track(sample: HealthSample) {
        if (lastPlugged != sample.plugged) history.clear()
        lastPlugged = sample.plugged
        history.addLast(Point(sample.atMs, sample.batteryPercent, sample.chargeCounterUah))
        while (history.size > 1 && sample.atMs - history.first().atMs > HealthThresholds.RATE_WINDOW_MS) {
            history.removeFirst()
        }
    }

    private fun step(axis: Axis, current: AxisState, raw: Level, nowMs: Long): AxisState {
        if (raw > current.level) {
            releasingSince.remove(axis)
            return AxisState(raw, nowMs)
        }
        if (!releases(current.level, raw)) {
            releasingSince.remove(axis)
            return current
        }
        val since = releasingSince.getOrPut(axis) { nowMs }
        if (nowMs - since < HealthThresholds.RELEASE_DWELL_MS) return current
        releasingSince.remove(axis)
        return AxisState(if (raw == Level.OK) Level.OK else Level.PAUSE, nowMs)
    }

    /** CRITICAL steps down once it is no longer critical; every other level waits for OK. */
    private fun releases(current: Level, raw: Level): Boolean = when (current) {
        Level.OK -> false
        Level.CRITICAL -> raw < Level.CRITICAL
        else -> raw == Level.OK
    }

    private fun thermalRaw(sample: HealthSample): Level {
        val status = sample.thermalStatus
        val raw = if (status != null) {
            when {
                status >= HealthThresholds.THERMAL_CRITICAL -> Level.CRITICAL
                status >= HealthThresholds.THERMAL_PAUSE -> Level.PAUSE
                status >= HealthThresholds.THERMAL_WARN -> Level.WARN
                else -> Level.OK
            }
        } else {
            when {
                sample.batteryTempC >= HealthThresholds.TEMP_CRITICAL_C -> Level.CRITICAL
                sample.batteryTempC >= HealthThresholds.TEMP_PAUSE_C -> Level.PAUSE
                sample.batteryTempC >= HealthThresholds.TEMP_WARN_C -> Level.WARN
                else -> Level.OK
            }
        }
        return if (raw == Level.PAUSE && !config.thermalAutoPause) Level.WARN else raw
    }

    private fun storageRaw(sample: HealthSample): Level {
        val floor = sample.floorBytes
        return when {
            floor > 0 && sample.freeBytes < floor * HealthThresholds.STORAGE_CRITICAL_FLOOR_FRACTION -> Level.CRITICAL
            !sample.floorHeld -> Level.PAUSE
            sample.pinnedNearCeiling -> Level.WARN
            (sample.storageProjectionDays ?: Float.MAX_VALUE) < HealthThresholds.STORAGE_WARN_DAYS -> Level.WARN
            else -> Level.OK
        }
    }

    private fun batteryRaw(sample: HealthSample, net: Float?): Level {
        val held = state.battery.level >= Level.PAUSE
        val raw = if (sample.plugged) pluggedBattery(sample, net, held) else unpluggedBattery(sample, net, held)
        return if (raw == Level.PAUSE && !config.batteryAutoPause) Level.WARN else raw
    }

    private fun unpluggedBattery(sample: HealthSample, net: Float?, held: Boolean): Level {
        val margin = if (held) HealthThresholds.BATTERY_RELEASE_MARGIN_PERCENT else 0
        val pauseAt = config.batteryPausePercent + margin
        val draining = net != null && -net > HealthThresholds.DRAIN_PAUSE_PERCENT_PER_HOUR
        return when {
            sample.batteryPercent <= pauseAt || draining -> Level.PAUSE
            sample.batteryPercent <= HealthThresholds.BATTERY_WARN_PERCENT -> Level.WARN
            else -> Level.OK
        }
    }

    private fun pluggedBattery(sample: HealthSample, net: Float?, held: Boolean): Level {
        val losing = net != null && net < -HealthThresholds.NET_DISCHARGE_EPSILON_PERCENT_PER_HOUR
        val rising = net != null && net > 0
        return when {
            losing && sample.batteryPercent <= HealthThresholds.BATTERY_PLUGGED_PAUSE_PERCENT -> Level.PAUSE
            held && !rising -> Level.PAUSE
            losing -> Level.WARN
            else -> Level.OK
        }
    }

    private fun capOf(thermal: Level, storage: Level, battery: Level): Pair<Cap, Axis?> {
        fun capFor(level: Level) = when (level) {
            Level.PAUSE -> Cap.NO_CONTINUOUS
            Level.CRITICAL -> Cap.NO_RECORDING
            else -> Cap.NONE
        }
        val worst = listOf(Axis.THERMAL to thermal, Axis.STORAGE to storage, Axis.BATTERY to battery)
            .map { (axis, level) -> axis to capFor(level) }
            .maxByOrNull { it.second }!!
        return if (worst.second == Cap.NONE) Cap.NONE to null else worst.second to worst.first
    }

    private fun netRatePercentPerHour(): Float? {
        if (history.size < HealthThresholds.MIN_RATE_SAMPLES) return null
        val first = history.first()
        val last = history.last()
        val span = last.atMs - first.atMs
        val useCounter = last.percent > 0 && history.all { it.counterUah > 0 }
        val minSpan = if (useCounter) HealthThresholds.MIN_SPAN_WITH_COUNTER_MS else HealthThresholds.MIN_SPAN_PERCENT_ONLY_MS
        if (span < minSpan) return null

        val capacityUah = if (useCounter) last.counterUah * 100.0 / last.percent else 0.0
        val xs = history.map { (it.atMs - first.atMs).toDouble() }
        val ys = history.map { if (useCounter) it.counterUah * 100.0 / capacityUah else it.percent.toDouble() }
        return (slope(xs, ys) * MS_PER_HOUR).toFloat()
    }

    private fun slope(xs: List<Double>, ys: List<Double>): Double {
        val meanX = xs.average()
        val meanY = ys.average()
        var covariance = 0.0
        var variance = 0.0
        for (i in xs.indices) {
            covariance += (xs[i] - meanX) * (ys[i] - meanY)
            variance += (xs[i] - meanX) * (xs[i] - meanX)
        }
        return if (variance == 0.0) 0.0 else covariance / variance
    }

    private companion object {
        const val MS_PER_HOUR = 3_600_000.0
    }
}
