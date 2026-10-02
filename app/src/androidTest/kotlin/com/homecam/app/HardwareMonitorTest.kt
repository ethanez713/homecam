package com.homecam.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CapabilityProvider
import com.homecam.app.camera.HardwareMonitor
import com.homecam.app.data.ClipStore
import com.homecam.core.health.Level
import com.homecam.proto.RuntimeStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareMonitorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thermalStatusSupportMatchesTheApiLevel() {
        val capabilities = CapabilityProvider.capabilities(context)
        assertTrue(capabilities.hardwareProtection)
        assertEquals(Build.VERSION.SDK_INT >= 29, capabilities.thermalStatusSupported)
    }

    @Test
    fun aSnapshotCarriesTheDeviceReadingsAndANonNegativeDrainRate() = runBlocking {
        val graph = AppGraph.get(context)
        val monitor = HardwareMonitor(graph, ClipStore(context, graph.database.clips()))
        monitor.start()
        try {
            repeat(4) { monitor.sample() }
        } finally {
            monitor.stop()
        }

        val status = monitor.fill(RuntimeStatus.newBuilder()).build()
        assertTrue(status.drainPercentPerHour >= 0f)
        assertTrue(status.floorBytes > 0)
        assertEquals(monitor.state.thermal.level >= Level.PAUSE, status.thermalThrottled)
        assertEquals(monitor.thermalStatusSupported, status.thermalStatus != RuntimeStatus.Thermal.THERMAL_UNKNOWN)
    }
}
