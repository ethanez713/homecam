package com.homecam.core

import com.homecam.core.crypto.JvmIdentitySigner
import com.homecam.core.pairing.CameraPairingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingInviteTest {

    private val camera = JvmIdentitySigner.generate()

    private fun session(now: Long = 1_700_000_000_000L) = CameraPairingSession(
        cameraIdentity = camera.identity,
        host = "192.168.1.50",
        port = 8443,
        cameraAlias = "Front door",
        startedAtMs = now,
    )

    @Test
    fun `an invite survives the QR round trip`() {
        val original = session()
        val decoded = CameraPairingSession.decodeInvite(original.inviteText())!!

        assertEquals("192.168.1.50", decoded.host)
        assertEquals(8443, decoded.port)
        assertEquals("Front door", decoded.cameraAlias)
        assertTrue(decoded.token.toByteArray().contentEquals(original.token))
        assertTrue(decoded.cameraPublicKey.toByteArray().contentEquals(camera.identity.spki))
        assertEquals(original.expiresAtMs, decoded.expiresAtMs)
    }

    @Test
    fun `an invite fits in a QR sparse enough to scan across a room`() {
        // Version 10-L in byte mode holds 274 bytes. Denser than that starts wanting a
        // steady hand and good light.
        val text = session().inviteText()
        assertTrue("invite was ${'$'}{text.length} chars", text.length <= 274)
    }

    @Test
    fun `text that is not an invite decodes to null rather than throwing`() {
        assertNull(CameraPairingSession.decodeInvite("https://example.com"))
        assertNull(CameraPairingSession.decodeInvite("homecam:not-base64!!"))
        assertNull(CameraPairingSession.decodeInvite("homecam:"))
        assertNull(CameraPairingSession.decodeInvite(""))
    }

    @Test
    fun `each pairing window mints a fresh token`() {
        assertNotEquals(
            session().token.toList(),
            session().token.toList(),
        )
    }

    @Test
    fun `a cancelled window stops accepting immediately`() {
        val now = 1_700_000_000_000L
        val window = session(now)
        window.cancel()
        assertEquals(CameraPairingSession.State.CANCELLED, window.state(now))
        assertTrue(!window.consume(now))
    }
}
