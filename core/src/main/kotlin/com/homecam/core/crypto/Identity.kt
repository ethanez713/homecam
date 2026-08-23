package com.homecam.core.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * A peer's public identity, as the X.509 SubjectPublicKeyInfo bytes that travel on the
 * wire. See `docs/DECISIONS.md` D1 for why this is EC P-256 rather than Ed25519.
 *
 * Accessors hand out copies: this object is the key the allowlist is compared against, so
 * a caller mutating the array it was handed must not be able to change what it matches.
 */
class IdentityKey(spki: ByteArray) {

    private val bytes: ByteArray = spki.copyOf()
    private val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    val spki: ByteArray get() = bytes.copyOf()
    val fingerprint: ByteArray get() = digest.copyOf()

    /** The `7K2P-QM4X-9DFA` form shown in the roster and the audit log. */
    val shortFingerprint: String = CrockfordBase32.groups(digest, chars = 12, groupSize = 4)

    fun toPublicKey(): PublicKey =
        KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))

    fun verify(data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(toPublicKey())
            update(data)
            verify(signature)
        }
    } catch (e: GeneralSecurityException) {
        false
    }

    override fun equals(other: Any?) = other is IdentityKey && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "IdentityKey($shortFingerprint)"

    companion object {
        /** Null rather than throwing, because the input is attacker-controlled wire bytes. */
        fun parse(spki: ByteArray): IdentityKey? = try {
            IdentityKey(spki).also { it.toPublicKey() }
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        const val KEY_ALGORITHM = "EC"
        const val CURVE = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

/**
 * Signing half of an identity. The private key lives in the Android Keystore on device
 * and is never handed to this interface — implementations hold a handle, not key bytes.
 */
interface IdentitySigner {
    val identity: IdentityKey
    fun sign(data: ByteArray): ByteArray
}
