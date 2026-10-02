package com.homecam.core

import com.homecam.core.crypto.AlertCrypto
import com.homecam.core.crypto.AlertLimits
import com.homecam.core.crypto.AlertPadding
import com.homecam.proto.AlertPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AlertPaddingTest {

    private val viewer = AlertCrypto.generateAgreementKeyPair()

    private fun alert(labels: Int = 1, thumbnail: Int = 0, alias: String = "Front door") = AlertPayload.newBuilder()
        .setAtMs(1_700_000_000_000L).setCameraAlias(alias).setCameraFingerprint("7K2P-QM4X-9DFA")
        .addAllLabels(List(labels) { "person" })
        .apply { if (thumbnail > 0) setThumbnailJpeg(com.google.protobuf.ByteString.copyFrom(ByteArray(thumbnail) { it.toByte() })) }
        .build()

    private fun bodySize(payload: AlertPayload): Int =
        Base64.getEncoder().encode(AlertCrypto.seal(viewer.public.encoded, payload.toByteArray())).size

    @Test
    fun `alerts of different content share a body size when they share a bucket`() {
        val small = listOf(
            alert(), alert(labels = 3), alert(alias = "Living room camera by the stairs"),
            alert(thumbnail = 100), alert(thumbnail = 300),
        ).map { bodySize(AlertPadding.pad(it)) }.distinct()
        assertEquals("sizes were $small", 1, small.size)
        assertTrue(bodySize(AlertPadding.pad(alert(thumbnail = 900))) > small.single())
    }

    @Test
    fun `a bare alert pads to the smallest bucket and a thumbnail alert to a larger one`() {
        assertEquals(512, AlertPadding.pad(alert()).serializedSize)
        assertEquals(1024, AlertPadding.pad(alert(thumbnail = 700)).serializedSize)
        assertEquals(2048, AlertPadding.pad(alert(thumbnail = AlertLimits.MAX_THUMBNAIL_BYTES - 700)).serializedSize)
    }

    @Test
    fun `padding never pushes a body past the push limit and never changes what it says`() {
        val full = alert(thumbnail = AlertLimits.MAX_THUMBNAIL_BYTES)
        val padded = AlertPadding.pad(full)
        assertTrue(bodySize(padded) <= AlertLimits.MAX_SEALED_BODY_BYTES)
        assertEquals(full.toBuilder().clearPadding().build(), padded.toBuilder().clearPadding().build())
    }

    @Test
    fun `an already padded payload is padded again to the same size, not grown`() {
        val once = AlertPadding.pad(alert(thumbnail = 400))
        assertEquals(once.serializedSize, AlertPadding.pad(once).serializedSize)
    }

    @Test
    fun `an older reader parses a padded alert and ignores the padding`() {
        val padded = AlertPadding.pad(alert())
        val parsed = AlertPayload.parseFrom(padded.toByteArray())
        assertEquals("Front door", parsed.cameraAlias)
        assertEquals(listOf("person"), parsed.labelsList)
    }
}
