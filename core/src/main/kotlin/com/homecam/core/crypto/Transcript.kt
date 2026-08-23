package com.homecam.core.crypto

import java.security.MessageDigest

/**
 * Canonical byte encoding of everything a handshake signature must cover
 * (`docs/DECISIONS.md` D3).
 */
class Transcript private constructor(private val bytes: ByteArray) {

    /**
     * Camera and viewer sign the same transcript under different role labels, so a
     * signature produced by one can never be replayed as the other's.
     */
    fun forRole(role: Role): ByteArray = role.label.toByteArray() + bytes

    /** Shown on both devices at the end of pairing; they must match (§2.3). */
    fun verificationPhrase(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("phrase".toByteArray() + bytes)
        return CrockfordBase32.groups(digest, chars = 8, groupSize = 4)
    }

    enum class Role(val label: String) { CAMERA("homecam-camera-v1"), VIEWER("homecam-viewer-v1") }

    companion object {
        /**
         * Binding the pinned certificate fingerprint in here is what stops a MITM that
         * terminates TLS with its own certificate from replaying a signature captured
         * from the real camera.
         */
        fun of(
            camera: IdentityKey,
            viewer: IdentityKey,
            cameraNonce: ByteArray,
            viewerNonce: ByteArray,
            certFingerprint: ByteArray,
            pairingToken: ByteArray = ByteArray(0),
        ): Transcript = Transcript(
            Canonical.encode(
                "homecam/1".toByteArray(),
                camera.spki,
                viewer.spki,
                cameraNonce,
                viewerNonce,
                certFingerprint,
                pairingToken,
            )
        )
    }
}
