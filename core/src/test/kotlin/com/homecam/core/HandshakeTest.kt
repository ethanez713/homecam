package com.homecam.core

import com.homecam.core.crypto.IdentityKey
import com.homecam.core.crypto.JvmIdentitySigner
import com.homecam.core.pairing.CameraPairingSession
import com.homecam.core.signaling.CameraHandshake
import com.homecam.core.signaling.ViewerHandshake
import com.homecam.proto.Bye
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTest {

    private val camera = JvmIdentitySigner.generate()
    private val viewer = JvmIdentitySigner.generate()
    private val certFingerprint = ByteArray(32) { it.toByte() }
    private var now = 1_000_000L
    private val allowlist = mutableSetOf<IdentityKey>()
    private var pairing: CameraPairingSession? = null

    private fun cameraSide() = CameraHandshake(
        signer = camera,
        certFingerprint = certFingerprint,
        isAllowed = allowlist::contains,
        activePairing = { pairing },
        nowMs = { now },
    )

    private fun openPairingWindow(): CameraPairingSession =
        CameraPairingSession(
            cameraIdentity = camera.identity,
            certFingerprint = certFingerprint,
            host = "192.168.1.50",
            port = 8443,
            cameraAlias = "Front door",
            startedAtMs = now,
        ).also { pairing = it }

    private fun runPairing(
        window: CameraPairingSession,
        viewerSigner: JvmIdentitySigner = viewer,
        viewerCertFingerprint: ByteArray = certFingerprint,
    ): Pair<CameraHandshake.Outcome, ViewerHandshake.Outcome?> {
        val server = cameraSide()
        val client = ViewerHandshake(
            signer = viewerSigner,
            expectedCamera = IdentityKey(window.invite().cameraPublicKey.toByteArray()),
            certFingerprint = viewerCertFingerprint,
            deviceLabel = "Ethan's S25",
            pairingToken = window.token,
        )
        val challenged = server.onHello(client.hello())
        if (challenged !is CameraHandshake.Outcome.Challenged) return challenged to null
        val authenticating = client.onChallenge(challenged.challenge)
        if (authenticating !is ViewerHandshake.Outcome.Authenticating) {
            return CameraHandshake.Outcome.Rejected(
                Bye.Reason.AUTH_FAILED, com.homecam.proto.AuthResult.getDefaultInstance()
            ) to authenticating
        }
        return server.onAuth(authenticating.auth) to authenticating
    }

    @Test
    fun `pairing succeeds and both sides show the same verification phrase`() {
        val window = openPairingWindow()
        val (server, client) = runPairing(window)
        val accepted = server as CameraHandshake.Outcome.Accepted
        val authenticating = client as ViewerHandshake.Outcome.Authenticating

        assertEquals(viewer.identity, accepted.viewer)
        assertEquals("Ethan's S25", accepted.label)
        assertTrue(accepted.pairedNow)
        assertEquals(authenticating.verificationPhrase, accepted.verificationPhrase)
        assertEquals(9, accepted.verificationPhrase.length)
    }

    @Test
    fun `a pairing token is single use`() {
        val window = openPairingWindow()
        assertTrue(runPairing(window).first is CameraHandshake.Outcome.Accepted)

        val second = runPairing(window, viewerSigner = JvmIdentitySigner.generate()).first
        assertEquals(
            Bye.Reason.PAIRING_EXPIRED,
            (second as CameraHandshake.Outcome.Rejected).reason,
        )
    }

    @Test
    fun `a pairing window expires after its ttl`() {
        val window = openPairingWindow()
        now += CameraPairingSession.DEFAULT_TTL_MS
        val outcome = runPairing(window).first
        assertEquals(
            Bye.Reason.PAIRING_EXPIRED,
            (outcome as CameraHandshake.Outcome.Rejected).reason,
        )
    }

    @Test
    fun `an unpaired key is refused before any application data`() {
        val stranger = JvmIdentitySigner.generate()
        val client = ViewerHandshake(stranger, camera.identity, certFingerprint, "Stranger")
        val outcome = cameraSide().onHello(client.hello())
        assertEquals(
            Bye.Reason.ACCESS_DENIED,
            (outcome as CameraHandshake.Outcome.Rejected).reason,
        )
    }

    @Test
    fun `a paired key reconnects without pairing mode`() {
        val window = openPairingWindow()
        val accepted = runPairing(window).first as CameraHandshake.Outcome.Accepted
        allowlist += accepted.viewer
        pairing = null

        val server = cameraSide()
        val client = ViewerHandshake(viewer, camera.identity, certFingerprint, "Ethan's S25")
        val challenged = server.onHello(client.hello()) as CameraHandshake.Outcome.Challenged
        val auth = client.onChallenge(challenged.challenge) as ViewerHandshake.Outcome.Authenticating
        assertTrue(server.onAuth(auth.auth) is CameraHandshake.Outcome.Accepted)
    }

    @Test
    fun `revoking between hello and auth denies the session`() {
        val window = openPairingWindow()
        val paired = runPairing(window).first as CameraHandshake.Outcome.Accepted
        allowlist += paired.viewer
        pairing = null

        val server = cameraSide()
        val client = ViewerHandshake(viewer, camera.identity, certFingerprint, "Ethan's S25")
        val challenged = server.onHello(client.hello()) as CameraHandshake.Outcome.Challenged
        val auth = client.onChallenge(challenged.challenge) as ViewerHandshake.Outcome.Authenticating
        allowlist.clear()

        val outcome = server.onAuth(auth.auth)
        assertEquals(
            Bye.Reason.ACCESS_DENIED,
            (outcome as CameraHandshake.Outcome.Rejected).reason,
        )
    }

    @Test
    fun `a viewer that pinned a different certificate refuses the camera signature`() {
        val window = openPairingWindow()
        val (_, client) = runPairing(window, viewerCertFingerprint = ByteArray(32) { 0x7F })
        assertEquals(
            Bye.Reason.AUTH_FAILED,
            (client as ViewerHandshake.Outcome.Rejected).reason,
        )
    }

    @Test
    fun `a substituted camera key is refused against the pinned one`() {
        val window = openPairingWindow()
        val impostor = JvmIdentitySigner.generate()
        val server = CameraHandshake(impostor, certFingerprint, { true }, { pairing }, { now })
        val client = ViewerHandshake(viewer, camera.identity, certFingerprint, "Ethan's S25", window.token)
        val challenged = server.onHello(client.hello()) as CameraHandshake.Outcome.Challenged
        val outcome = client.onChallenge(challenged.challenge)
        assertEquals(
            Bye.Reason.ACCESS_DENIED,
            (outcome as ViewerHandshake.Outcome.Rejected).reason,
        )
    }

    @Test
    fun `different pairings produce different verification phrases`() {
        val first = openPairingWindow()
        val a = runPairing(first).first as CameraHandshake.Outcome.Accepted
        now += 1
        val second = openPairingWindow()
        val b = runPairing(second, viewerSigner = JvmIdentitySigner.generate()).first
            as CameraHandshake.Outcome.Accepted
        assertNotEquals(a.verificationPhrase, b.verificationPhrase)
    }

    @Test
    fun `a mismatched protocol version is refused as incompatible`() {
        val client = ViewerHandshake(viewer, camera.identity, certFingerprint, "Ethan's S25")
        val hello = client.hello().toBuilder().setProtocolVersion(99).build()
        val outcome = cameraSide().onHello(hello)
        assertEquals(
            Bye.Reason.INCOMPATIBLE,
            (outcome as CameraHandshake.Outcome.Rejected).reason,
        )
    }

    @Test
    fun `a malformed public key is refused rather than crashing`() {
        val client = ViewerHandshake(viewer, camera.identity, certFingerprint, "Ethan's S25")
        val hello = client.hello().toBuilder()
            .setViewerPublicKey(com.google.protobuf.ByteString.copyFrom(ByteArray(17) { 0x41 }))
            .build()
        val outcome = cameraSide().onHello(hello)
        assertEquals(
            Bye.Reason.AUTH_FAILED,
            (outcome as CameraHandshake.Outcome.Rejected).reason,
        )
    }
}
