package com.homecam.core.signaling

import com.google.protobuf.ByteString
import com.homecam.core.crypto.IdentityKey
import com.homecam.core.crypto.IdentitySigner
import com.homecam.core.crypto.Transcript
import com.homecam.core.pairing.CameraPairingSession
import com.homecam.proto.Auth
import com.homecam.proto.AuthResult
import com.homecam.proto.Bye
import com.homecam.proto.Challenge
import com.homecam.proto.Hello
import java.security.SecureRandom

/**
 * The camera's half of the mutual challenge-response (§2.2). One instance per inbound
 * connection; it refuses an unrecognised key before any application data is exchanged.
 *
 * [isAllowed] and [activePairing] are read at handshake time rather than captured up
 * front, so a revoke that lands mid-handshake still takes effect.
 */
class CameraHandshake(
    private val signer: IdentitySigner,
    private val certFingerprint: ByteArray,
    private val isAllowed: (IdentityKey) -> Boolean,
    private val activePairing: () -> CameraPairingSession?,
    private val nowMs: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    sealed interface Outcome {
        data class Challenged(val challenge: Challenge) : Outcome
        data class Accepted(
            val viewer: IdentityKey,
            val label: String,
            val verificationPhrase: String,
            val pairedNow: Boolean,
            val result: AuthResult,
        ) : Outcome
        data class Rejected(val reason: Bye.Reason, val result: AuthResult) : Outcome
    }

    private var viewer: IdentityKey? = null
    private var transcript: Transcript? = null
    private var pairingAttempt = false
    private var cameraNonce: ByteArray = ByteArray(0)

    fun onHello(hello: Hello): Outcome {
        if (hello.protocolVersion != PROTOCOL_VERSION) return reject(Bye.Reason.INCOMPATIBLE)
        if (hello.viewerNonce.size() != NONCE_BYTES) return reject(Bye.Reason.AUTH_FAILED)

        val peer = IdentityKey.parse(hello.viewerPublicKey.toByteArray())
            ?: return reject(Bye.Reason.AUTH_FAILED)

        pairingAttempt = hello.pairing
        val pairing = activePairing()
        if (pairingAttempt) {
            if (pairing == null || pairing.state(nowMs()) != CameraPairingSession.State.ACTIVE) {
                return reject(Bye.Reason.PAIRING_EXPIRED)
            }
        } else if (!isAllowed(peer)) {
            return reject(Bye.Reason.ACCESS_DENIED)
        }

        cameraNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        // A pairing handshake binds the one-time token into the signed transcript, so a
        // relay that did not scan the QR cannot produce a matching signature.
        val token = if (pairingAttempt) pairing!!.token else ByteArray(0)
        val built = Transcript.of(
            camera = signer.identity,
            viewer = peer,
            cameraNonce = cameraNonce,
            viewerNonce = hello.viewerNonce.toByteArray(),
            certFingerprint = certFingerprint,
            pairingToken = token,
        )
        viewer = peer
        transcript = built

        return Outcome.Challenged(
            Challenge.newBuilder()
                .setCameraPublicKey(ByteString.copyFrom(signer.identity.spki))
                .setCameraNonce(ByteString.copyFrom(cameraNonce))
                .setSignature(ByteString.copyFrom(signer.sign(built.forRole(Transcript.Role.CAMERA))))
                .build()
        )
    }

    fun onAuth(auth: Auth): Outcome {
        val peer = viewer ?: return reject(Bye.Reason.AUTH_FAILED)
        val built = transcript ?: return reject(Bye.Reason.AUTH_FAILED)

        if (!peer.verify(built.forRole(Transcript.Role.VIEWER), auth.signature.toByteArray())) {
            return reject(Bye.Reason.AUTH_FAILED)
        }

        if (pairingAttempt) {
            val pairing = activePairing() ?: return reject(Bye.Reason.PAIRING_EXPIRED)
            if (!pairing.consume(nowMs())) return reject(Bye.Reason.PAIRING_EXPIRED)
        } else if (!isAllowed(peer)) {
            return reject(Bye.Reason.ACCESS_DENIED)
        }

        val phrase = built.verificationPhrase()
        return Outcome.Accepted(
            viewer = peer,
            label = auth.deviceLabel,
            verificationPhrase = phrase,
            pairedNow = pairingAttempt,
            result = AuthResult.newBuilder().setOk(true).setVerificationPhrase(phrase).build(),
        )
    }

    private fun reject(reason: Bye.Reason) = Outcome.Rejected(
        reason,
        AuthResult.newBuilder().setOk(false).setReason(reason).build(),
    )

    companion object {
        const val PROTOCOL_VERSION = 1
        const val NONCE_BYTES = 32
    }
}
