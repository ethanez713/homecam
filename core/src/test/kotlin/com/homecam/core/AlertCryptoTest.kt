package com.homecam.core

import com.homecam.core.crypto.AlertCrypto
import com.homecam.proto.AlertPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertCryptoTest {

    private val viewer = AlertCrypto.generateAgreementKeyPair()
    private val viewerSpki = viewer.public.encoded

    private val alert = AlertPayload.newBuilder()
        .setAtMs(1_700_000_000_000L)
        .setCameraAlias("Front door")
        .setCameraFingerprint("7K2P-QM4X-9DFA")
        .addLabels("person")
        .setClipId("clip-1")
        .setThumbnailJpeg(com.google.protobuf.ByteString.copyFrom(ByteArray(512) { it.toByte() }))
        .build()

    @Test
    fun `an alert round-trips`() {
        val sealed = AlertCrypto.seal(viewerSpki, alert.toByteArray())
        val opened = AlertCrypto.open(viewer.private, viewerSpki, sealed)
        assertNotNull(opened)
        assertEquals(alert, AlertPayload.parseFrom(opened))
    }

    @Test
    fun `the plaintext does not appear in the ciphertext`() {
        val sealed = AlertCrypto.seal(viewerSpki, alert.toByteArray())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("Front door"))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("7K2P-QM4X-9DFA"))
    }

    @Test
    fun `another viewer cannot open it`() {
        val stranger = AlertCrypto.generateAgreementKeyPair()
        val sealed = AlertCrypto.seal(viewerSpki, alert.toByteArray())
        assertNull(AlertCrypto.open(stranger.private, stranger.public.encoded, sealed))
    }

    @Test
    fun `every message uses a fresh ephemeral key`() {
        val first = AlertCrypto.seal(viewerSpki, alert.toByteArray())
        val second = AlertCrypto.seal(viewerSpki, alert.toByteArray())
        assertFalse(first.contentEquals(second))
        // The ephemeral public key sits in the header; identical headers would mean a
        // reused key and no forward secrecy.
        assertFalse(first.copyOfRange(0, 40).contentEquals(second.copyOfRange(0, 40)))
    }

    @Test
    fun `a flipped ciphertext bit is rejected rather than decrypted`() {
        val sealed = AlertCrypto.seal(viewerSpki, alert.toByteArray())
        sealed[sealed.lastIndex - 5] = (sealed[sealed.lastIndex - 5].toInt() xor 0x01).toByte()
        assertNull(AlertCrypto.open(viewer.private, viewerSpki, sealed))
    }

    @Test
    fun `a swapped ephemeral key is rejected - it is bound into the derivation`() {
        val sealed = AlertCrypto.seal(viewerSpki, alert.toByteArray())
        val other = AlertCrypto.generateAgreementKeyPair().public.encoded
        val length = ((sealed[1].toInt() and 0xFF) shl 8) or (sealed[2].toInt() and 0xFF)
        assertEquals(length, other.size)
        other.copyInto(sealed, 3)
        assertNull(AlertCrypto.open(viewer.private, viewerSpki, sealed))
    }

    @Test
    fun `a truncated envelope is rejected rather than crashing`() {
        val sealed = AlertCrypto.seal(viewerSpki, alert.toByteArray())
        assertNull(AlertCrypto.open(viewer.private, viewerSpki, sealed.copyOf(sealed.size / 2)))
        assertNull(AlertCrypto.open(viewer.private, viewerSpki, ByteArray(0)))
        assertNull(AlertCrypto.open(viewer.private, viewerSpki, byteArrayOf(1, 0)))
    }

    @Test
    fun `an envelope from a future version is refused`() {
        val sealed = AlertCrypto.seal(viewerSpki, alert.toByteArray())
        sealed[0] = 99
        assertNull(AlertCrypto.open(viewer.private, viewerSpki, sealed))
    }

    @Test
    fun `an empty payload still seals and opens`() {
        val sealed = AlertCrypto.seal(viewerSpki, ByteArray(0))
        assertArrayEquals(ByteArray(0), AlertCrypto.open(viewer.private, viewerSpki, sealed))
    }

    @Test
    fun `a large thumbnail survives`() {
        val big = alert.toBuilder()
            .setThumbnailJpeg(com.google.protobuf.ByteString.copyFrom(ByteArray(200_000) { (it % 251).toByte() }))
            .build()
        val opened = AlertCrypto.open(
            viewer.private, viewerSpki, AlertCrypto.seal(viewerSpki, big.toByteArray()),
        )
        assertTrue(AlertPayload.parseFrom(opened).thumbnailJpeg.size() == 200_000)
    }

    private fun offCurve(spki: ByteArray): ByteArray =
        spki.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }

    @Test
    fun `a genuine key is on the curve and a nudged y coordinate is not`() {
        val key = viewer.public as java.security.interfaces.ECPublicKey
        assertTrue(AlertCrypto.onCurve(key.w.affineX, key.w.affineY, key.params))
        assertFalse(AlertCrypto.onCurve(key.w.affineX, key.w.affineY + java.math.BigInteger.ONE, key.params))
        assertFalse(AlertCrypto.onCurve(java.math.BigInteger.ZERO, java.math.BigInteger.ZERO, key.params))
        assertFalse(AlertCrypto.onCurve(key.params.curve.field.fieldSize.toBigInteger(), key.w.affineY, key.params))
    }

    @Test
    fun `an ephemeral key off the curve is refused as an ordinary failed open`() {
        val sealed = AlertCrypto.seal(viewerSpki, alert.toByteArray())
        val length = ((sealed[1].toInt() and 0xFF) shl 8) or (sealed[2].toInt() and 0xFF)
        val ephemeral = sealed.copyOfRange(3, 3 + length)
        offCurve(ephemeral).copyInto(sealed, 3)
        assertNull(AlertCrypto.open(viewer.private, viewerSpki, sealed))
    }

    @Test
    fun `sealing to a recipient key that is off the curve fails`() {
        org.junit.Assert.assertThrows(java.security.GeneralSecurityException::class.java) {
            AlertCrypto.seal(offCurve(viewerSpki), alert.toByteArray())
        }
    }

    @Test
    fun `a key on another curve is refused`() {
        val other = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp384r1")) }
            .generateKeyPair().public.encoded
        org.junit.Assert.assertThrows(java.security.GeneralSecurityException::class.java) { AlertCrypto.publicKeyFrom(other) }
    }
}
