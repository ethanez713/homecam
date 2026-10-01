package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.net.CameraAddress
import com.homecam.app.net.CameraLocator
import com.homecam.app.net.DiscoveredCamera
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A camera on a new DHCP lease is found by mDNS. An NSD browse never completes on its own,
 * so the locator has to act on candidates as they arrive — collecting a full list first
 * only ever ends in the discovery timeout, which throws the list away.
 */
@RunWith(AndroidJUnit4::class)
class CameraLocatorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pin = byteArrayOf(1, 2, 3)

    /** Like NsdManager: reports what it has found, then keeps browsing until cancelled. */
    private fun browse(vararg found: DiscoveredCamera) = flow {
        found.forEach { emit(it) }
        awaitCancellation()
    }

    private fun locator(answering: String, vararg found: DiscoveredCamera) = CameraLocator(
        context = context,
        discover = { browse(*found) },
        probe = { host, _, _ -> host == answering },
    )

    @Test
    fun aCameraThatMovedIsFoundByMdnsWhileTheBrowseIsStillOpen() = runBlocking {
        val moved = locator("192.168.0.18", DiscoveredCamera("hc-a", "192.168.0.18", 8443))

        val address = moved.locate(pin, lastHost = "192.168.0.17", lastPort = 8443)

        assertEquals(CameraAddress("192.168.0.18", 8443), address)
    }

    @Test
    fun aStrangerAdvertisingTheSameServiceIsSkipped() = runBlocking {
        val crowded = locator(
            "192.168.0.18",
            DiscoveredCamera("hc-stranger", "192.168.0.40", 8443),
            DiscoveredCamera("hc-a", "192.168.0.18", 8443),
        )

        val address = crowded.locate(pin, lastHost = "", lastPort = 0)

        assertEquals(CameraAddress("192.168.0.18", 8443), address)
    }

    @Test
    fun offTheHomeNetworkTheRemoteAddressIsTriedAfterDiscoveryGivesUp() = runBlocking {
        val away = locator("cam.tailnet.ts.net")

        val address = away.locate(
            pin, lastHost = "192.168.0.18", lastPort = 8443,
            remoteHost = "cam.tailnet.ts.net", discoveryTimeoutMs = 500,
        )

        assertEquals(CameraAddress("cam.tailnet.ts.net", 8443), address)
    }
}
