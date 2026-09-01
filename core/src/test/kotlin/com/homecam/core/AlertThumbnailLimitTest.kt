package com.homecam.core

import com.homecam.core.crypto.AlertCrypto
import com.homecam.core.crypto.AlertLimits
import com.homecam.proto.AlertPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Phase 1.5: a detection alert carries a thumbnail, and the sealed, Base64'd body has to
 * fit the push provider. This pins the budget so a change to the crypto envelope or the
 * thumbnail ceiling that would overflow it fails here rather than at delivery time.
 */
class AlertThumbnailLimitTest {

    private val viewer = AlertCrypto.generateAgreementKeyPair()

    private fun payloadWithThumbnail(bytes: Int) = AlertPayload.newBuilder()
        .setAtMs(1_700_000_000_000L)
        .setCameraAlias("Front door — a fairly long camera alias")
        .setCameraFingerprint("7K2P-QM4X-9DFA")
        .addLabels("person")
        .setThumbnailJpeg(com.google.protobuf.ByteString.copyFrom(ByteArray(bytes) { it.toByte() }))
        .build()

    @Test
    fun `a maximum-size thumbnail seals under the push-body limit`() {
        val sealed = AlertCrypto.seal(viewer.public.encoded, payloadWithThumbnail(AlertLimits.MAX_THUMBNAIL_BYTES).toByteArray())
        val body = Base64.getEncoder().encode(sealed)
        assertTrue(
            "sealed body ${body.size} exceeds ${AlertLimits.MAX_SEALED_BODY_BYTES}",
            body.size <= AlertLimits.MAX_SEALED_BODY_BYTES,
        )
    }

    @Test
    fun `the sealed alert still opens with its thumbnail intact`() {
        val payload = payloadWithThumbnail(AlertLimits.MAX_THUMBNAIL_BYTES)
        val sealed = AlertCrypto.seal(viewer.public.encoded, payload.toByteArray())
        val opened = AlertCrypto.open(viewer.private, viewer.public.encoded, sealed)
        assertEquals(payload, AlertPayload.parseFrom(opened))
    }
}
