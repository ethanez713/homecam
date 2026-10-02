package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homecam.core.crypto.AlertCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The on-curve check reads curve parameters from the platform provider, which JVM tests never use. */
@RunWith(AndroidJUnit4::class)
class AlertCryptoDeviceTest {

    private val viewer = AlertCrypto.generateAgreementKeyPair()
    private val spki = viewer.public.encoded

    @Test
    fun anAlertSealedAndOpenedOnThePlatformProviderRoundTrips() {
        val message = ByteArray(300) { it.toByte() }
        val opened = AlertCrypto.open(viewer.private, spki, AlertCrypto.seal(spki, message))
        assertArrayEquals(message, opened)
    }

    @Test
    fun anEphemeralKeyOffTheCurveIsRefusedOnThePlatformProviderToo() {
        val sealed = AlertCrypto.seal(spki, ByteArray(16))
        val length = ((sealed[1].toInt() and 0xFF) shl 8) or (sealed[2].toInt() and 0xFF)
        val last = 3 + length - 1
        sealed[last] = (sealed[last].toInt() xor 0x01).toByte()
        assertNull(AlertCrypto.open(viewer.private, spki, sealed))
    }
}
