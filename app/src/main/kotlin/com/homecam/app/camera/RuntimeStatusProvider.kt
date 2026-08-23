package com.homecam.app.camera

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import android.os.SystemClock
import com.homecam.app.AppGraph
import com.homecam.proto.RuntimeStatus

/**
 * The numbers §9 says to graph early: thermals on a 2020 phone under continuous encode
 * are the risk most likely to decide whether this design works at all, so the instrument
 * exists before the load does.
 */
class RuntimeStatusProvider(private val graph: AppGraph, private val node: CameraNode) {

    private val startedAtMs = SystemClock.elapsedRealtime()

    var captureFps: Float = 0f
    var detectorFps: Float = 0f
    var thermalThrottled: Boolean = false

    fun snapshot(clipBytes: Long): RuntimeStatus {
        val battery = graph.app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tenthsOfDegrees = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0

        val stat = StatFs(graph.app.filesDir.absolutePath)

        return RuntimeStatus.newBuilder()
            .setBatteryPercent(if (level >= 0 && scale > 0) level * 100f / scale else -1f)
            .setCharging(plugged)
            .setBatteryTempC(tenthsOfDegrees / 10f)
            .setFreeBytes(stat.availableBytes)
            .setClipBytes(clipBytes)
            .setCaptureFps(captureFps)
            .setDetectorFps(detectorFps)
            .setUptimeMs(SystemClock.elapsedRealtime() - startedAtMs)
            .setArmed(graph.settings.current.armed)
            .setViewersWithAccess(node.accessCount.value)
            .setCameraAlias(graph.settings.current.alias)
            .setThermalThrottled(thermalThrottled)
            .build()
    }
}
