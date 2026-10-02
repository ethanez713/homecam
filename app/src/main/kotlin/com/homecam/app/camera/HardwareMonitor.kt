package com.homecam.app.camera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.homecam.app.AppGraph
import com.homecam.app.data.ClipStore
import com.homecam.app.data.toRetentionLimits
import com.homecam.core.health.HealthConfig
import com.homecam.core.health.HealthPolicy
import com.homecam.core.health.HealthProtocol.setHealth
import com.homecam.core.health.HealthProtocol.toProto
import com.homecam.core.health.HealthSample
import com.homecam.core.health.HealthState
import com.homecam.core.health.Level
import com.homecam.core.health.ThermalStatus
import com.homecam.proto.RuntimeStatus
import kotlinx.coroutines.launch

/**
 * Feeds the camera's own heat, charge and storage to [HealthPolicy] and keeps the answer.
 * It samples on the service's existing rollover tick, and on API 29+ also the moment the
 * platform reports a thermal change, so a jump to SEVERE does not wait for the next tick.
 */
class HardwareMonitor(
    private val graph: AppGraph,
    private val clips: ClipStore,
    private val onChange: (previous: HealthState, current: HealthState) -> Unit = { _, _ -> },
) {
    private val policy = HealthPolicy()
    private val lock = Any()

    @Volatile var state: HealthState = HealthState.INITIAL
        private set
    @Volatile var thermalStatus: ThermalStatus? = null
        private set
    @Volatile var thermalHeadroom: Float = Float.NaN
        private set
    @Volatile var batteryHealth: Int = 0
        private set
    @Volatile private var storage = Storage()

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    val thermalStatusSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private data class Storage(val pinnedBytes: Long = 0, val floorBytes: Long = 0, val floorHeld: Boolean = true)

    fun start() {
        if (!thermalStatusSupported) return
        val power = graph.app.getSystemService(PowerManager::class.java)
        thermalStatus = ThermalStatus.fromPlatform(power.currentThermalStatus)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            thermalStatus = ThermalStatus.fromPlatform(status)
            graph.scope.launch { sample() }
        }
        thermalListener = listener
        power.addThermalStatusListener(Runnable::run, listener)
    }

    fun stop() {
        val listener = thermalListener ?: return
        graph.app.getSystemService(PowerManager::class.java).removeThermalStatusListener(listener)
        thermalListener = null
    }

    suspend fun sample() {
        val battery = graph.app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val manager = graph.app.getSystemService(BatteryManager::class.java)
        val reading = readStorage()
        val headroom = readHeadroom()
        val sample = HealthSample(
            atMs = System.currentTimeMillis(),
            batteryPercent = percentOf(battery),
            chargeCounterUah = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            batteryTempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            thermalStatus = thermalStatus,
            freeBytes = reading.freeBytes,
            floorBytes = reading.storage.floorBytes,
            floorHeld = reading.storage.floorHeld,
            pinnedNearCeiling = reading.pinnedNearCeiling,
            storageProjectionDays = reading.projectionDays,
        )
        batteryHealth = battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        thermalHeadroom = headroom
        storage = reading.storage

        val (previous, current) = synchronized(lock) {
            val before = state
            state = policy.update(sample)
            before to state
        }
        if (previous != current) onChange(previous, current)
    }

    fun configure(config: HealthConfig) {
        synchronized(lock) { policy.config = config }
    }

    fun fill(builder: RuntimeStatus.Builder): RuntimeStatus.Builder = builder
        .setThermalStatus(thermalStatus.toProto())
        .setThermalHeadroom(thermalHeadroom)
        .setBatteryHealth(batteryHealth)
        .setPinnedBytes(storage.pinnedBytes)
        .setFloorBytes(storage.floorBytes)
        .setFloorHeld(storage.floorHeld)
        .setThermalThrottled(state.thermal.level >= Level.PAUSE)
        .setHealth(state, graph.settings.current.recordingMode)

    private class StorageReading(
        val freeBytes: Long,
        val storage: Storage,
        val pinnedNearCeiling: Boolean,
        val projectionDays: Float?,
    )

    private suspend fun readStorage(): StorageReading {
        val retention = graph.settings.current.retention
        val floor = retention.toRetentionLimits().minFreeBytes
        val free = clips.directory.usableSpace
        val plan = ClipStore.lastPlan
        // A prune that could not reach the floor is the signal; once free space is back
        // above the floor the stale plan no longer matters.
        val held = plan?.freeFloorHeld ?: true || free >= floor
        return StorageReading(
            freeBytes = free,
            storage = Storage(plan?.pinnedBytes ?: 0, floor, held),
            pinnedNearCeiling = plan?.pinnedNearCeiling ?: false,
            projectionDays = clips.projectedRetentionDays(retention)?.toFloat(),
        )
    }

    private fun readHeadroom(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        return graph.app.getSystemService(PowerManager::class.java).getThermalHeadroom(0)
    }

    private fun percentOf(battery: Intent?): Int {
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else UNREADABLE_PERCENT
    }

    private companion object {
        // An unreadable battery must not look drained and pause recording.
        const val UNREADABLE_PERCENT = 100
    }
}
