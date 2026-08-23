package com.homecam.core

import com.homecam.core.signaling.DtlsFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FINGERPRINT =
    "sha-256 5A:96:9F:2E:4B:41:6D:2C:8B:C1:0D:33:7F:9A:2E:11:04:6C:8F:31:AA:5D:9E:77:12:34:56:78:9A:BC:DE:F0"

private fun sdp(vararg lines: String) = buildString {
    appendLine("v=0")
    appendLine("o=- 4611731400430051336 2 IN IP4 127.0.0.1")
    appendLine("s=-")
    appendLine("t=0 0")
    appendLine("m=video 9 UDP/TLS/RTP/SAVPF 96")
    appendLine("c=IN IP4 0.0.0.0")
    appendLine("a=ice-ufrag:4ZcD")
    lines.forEach { appendLine(it) }
    appendLine("a=setup:actpass")
}

class DtlsFingerprintTest {

    @Test
    fun `the fingerprint is read out of the sdp`() {
        assertEquals(FINGERPRINT, DtlsFingerprint.parse(sdp("a=fingerprint:$FINGERPRINT")))
    }

    @Test
    fun `a repeated identical fingerprint across m-sections is still one commitment`() {
        val parsed = DtlsFingerprint.parse(
            sdp("a=fingerprint:$FINGERPRINT", "a=fingerprint:$FINGERPRINT")
        )
        assertEquals(FINGERPRINT, parsed)
    }

    @Test
    fun `two different fingerprints are ambiguous and refused`() {
        val other = FINGERPRINT.replace("5A:96", "AA:BB")
        assertNull(DtlsFingerprint.parse(sdp("a=fingerprint:$FINGERPRINT", "a=fingerprint:$other")))
    }

    @Test
    fun `an sdp with no fingerprint yields null`() {
        assertNull(DtlsFingerprint.parse(sdp("a=rtpmap:96 H264/90000")))
    }

    @Test
    fun `a matching fingerprint passes`() {
        assertTrue(DtlsFingerprint.matches(FINGERPRINT, sdp("a=fingerprint:$FINGERPRINT")))
    }

    @Test
    fun `case and hash-name spelling do not decide a security check`() {
        val shouty = "SHA-256 " + FINGERPRINT.substringAfter(' ').lowercase()
        assertTrue(DtlsFingerprint.matches(FINGERPRINT, sdp("a=fingerprint:$shouty")))
    }

    @Test
    fun `a substituted fingerprint fails - this is the attack the check exists for`() {
        val attacker = FINGERPRINT.replace("5A:96", "AA:BB")
        assertFalse(DtlsFingerprint.matches(FINGERPRINT, sdp("a=fingerprint:$attacker")))
    }

    @Test
    fun `an sdp that commits to nothing fails rather than passing by omission`() {
        assertFalse(DtlsFingerprint.matches(FINGERPRINT, sdp("a=rtpmap:96 H264/90000")))
    }

    @Test
    fun `a sha-1 fingerprint is refused even when it matches what was committed`() {
        val weak = "sha-1 " + FINGERPRINT.substringAfter(' ')
        assertFalse(DtlsFingerprint.isAcceptable(weak))
        assertFalse(DtlsFingerprint.matches(weak, sdp("a=fingerprint:$weak")))
    }

    @Test
    fun `a fingerprint on a CRLF sdp is still found`() {
        val text = "v=0\r\na=fingerprint:$FINGERPRINT\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
        assertEquals(FINGERPRINT, DtlsFingerprint.parse(text))
    }
}

class DtlsCommitmentTest {

    private val other = FINGERPRINT.replace("5A:96", "AA:BB")

    @Test
    fun `the first sdp establishes the commitment`() {
        val commitment = com.homecam.core.signaling.DtlsCommitment()
        assertEquals(
            com.homecam.core.signaling.DtlsCommitment.Result.ACCEPTED,
            commitment.check(sdp("a=fingerprint:$FINGERPRINT"), FINGERPRINT),
        )
        assertEquals(FINGERPRINT, commitment.committed)
    }

    @Test
    fun `a re-offer with the same identity is accepted`() {
        val commitment = com.homecam.core.signaling.DtlsCommitment()
        commitment.check(sdp("a=fingerprint:$FINGERPRINT"), FINGERPRINT)
        assertEquals(
            com.homecam.core.signaling.DtlsCommitment.Result.ACCEPTED,
            commitment.check(sdp("a=fingerprint:$FINGERPRINT", "a=ice-restart"), FINGERPRINT),
        )
    }

    @Test
    fun `a re-offer that swaps the dtls identity mid-session is refused`() {
        val commitment = com.homecam.core.signaling.DtlsCommitment()
        commitment.check(sdp("a=fingerprint:$FINGERPRINT"), FINGERPRINT)
        assertEquals(
            com.homecam.core.signaling.DtlsCommitment.Result.CHANGED,
            commitment.check(sdp("a=fingerprint:$other"), other),
        )
        assertEquals(FINGERPRINT, commitment.committed)
    }

    @Test
    fun `an sdp that disagrees with what the peer declared is refused`() {
        val commitment = com.homecam.core.signaling.DtlsCommitment()
        assertEquals(
            com.homecam.core.signaling.DtlsCommitment.Result.INCONSISTENT,
            commitment.check(sdp("a=fingerprint:$other"), FINGERPRINT),
        )
        assertNull(commitment.committed)
    }

    @Test
    fun `an sdp with no fingerprint is refused`() {
        val commitment = com.homecam.core.signaling.DtlsCommitment()
        assertEquals(
            com.homecam.core.signaling.DtlsCommitment.Result.MISSING,
            commitment.check(sdp("a=rtpmap:96 H264/90000"), FINGERPRINT),
        )
    }

    @Test
    fun `a weak hash is refused before anything else is considered`() {
        val commitment = com.homecam.core.signaling.DtlsCommitment()
        val weak = "sha-1 " + FINGERPRINT.substringAfter(' ')
        assertEquals(
            com.homecam.core.signaling.DtlsCommitment.Result.WEAK_HASH,
            commitment.check(sdp("a=fingerprint:$weak"), weak),
        )
    }

    @Test
    fun `a peer that declares nothing still has its sdp pinned`() {
        val commitment = com.homecam.core.signaling.DtlsCommitment()
        assertEquals(
            com.homecam.core.signaling.DtlsCommitment.Result.ACCEPTED,
            commitment.check(sdp("a=fingerprint:$FINGERPRINT"), ""),
        )
        assertEquals(
            com.homecam.core.signaling.DtlsCommitment.Result.CHANGED,
            commitment.check(sdp("a=fingerprint:$other"), ""),
        )
    }
}
