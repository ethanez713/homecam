package com.homecam.core.signaling

import com.google.protobuf.ByteString
import com.homecam.core.crypto.IdentityKey
import com.homecam.core.crypto.IdentitySigner
import com.homecam.core.crypto.Transcript
import com.homecam.proto.Auth
import com.homecam.proto.AuthResult
import com.homecam.proto.Bye
import com.homecam.proto.Challenge
import com.homecam.proto.Hello
import java.security.SecureRandom

/**
 * The viewer's half of the mutual challenge-response (§2.2).
 *
 * [expectedCamera] is null only during pairing, when the camera's key arrives in the QR
 * and is being learned; on every connection thereafter it is the pinned key and a
 * mismatch aborts before anything is signed.
 */
class ViewerHandshake(
    private val signer: IdentitySigner,
    private val expectedCamera: IdentityKey?,
    private val certFingerprint: ByteArray,
    private val deviceLabel: String,
    private val pairingToken: ByteArray = ByteArray(0),
    private val random: SecureRandom = SecureRandom(),
) {
    sealed interface Outcome {
        data class Authenticating(val auth: Auth, val verificationPhrase: String) : Outcome
        data class Rejected(val reason: Bye.Reason) : Outcome
    }

    private val viewerNonce = ByteArray(CameraHandshake.NONCE_BYTES).also(random::nextBytes)
    private val pairing = pairingToken.isNotEmpty()

    fun hello(): Hello = Hello.newBuilder()
        .setViewerPublicKey(ByteString.copyFrom(signer.identity.spki))
        .setViewerNonce(ByteString.copyFrom(viewerNonce))
        .setProtocolVersion(CameraHandshake.PROTOCOL_VERSION)
        .setPairing(pairing)
        .build()

    fun onChallenge(challenge: Challenge): Outcome {
        if (challenge.cameraNonce.size() != CameraHandshake.NONCE_BYTES) {
            return Outcome.Rejected(Bye.Reason.AUTH_FAILED)
        }
        val camera = IdentityKey.parse(challenge.cameraPublicKey.toByteArray())
            ?: return Outcome.Rejected(Bye.Reason.AUTH_FAILED)
        if (expectedCamera != null && camera != expectedCamera) {
            return Outcome.Rejected(Bye.Reason.ACCESS_DENIED)
        }

        val transcript = Transcript.of(
            camera = camera,
            viewer = signer.identity,
            cameraNonce = challenge.cameraNonce.toByteArray(),
            viewerNonce = viewerNonce,
            certFingerprint = certFingerprint,
            pairingToken = pairingToken,
        )
        if (!camera.verify(transcript.forRole(Transcript.Role.CAMERA), challenge.signature.toByteArray())) {
            return Outcome.Rejected(Bye.Reason.AUTH_FAILED)
        }

        return Outcome.Authenticating(
            Auth.newBuilder()
                .setSignature(ByteString.copyFrom(signer.sign(transcript.forRole(Transcript.Role.VIEWER))))
                .setDeviceLabel(deviceLabel)
                .build(),
            transcript.verificationPhrase(),
        )
    }

    /** The camera has the last word; it can still refuse after a valid signature. */
    fun onAuthResult(result: AuthResult): Bye.Reason? =
        if (result.ok) null else result.reason.takeIf { it != Bye.Reason.UNSPECIFIED } ?: Bye.Reason.AUTH_FAILED
}
