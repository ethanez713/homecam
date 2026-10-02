package com.homecam.core.crypto

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealing an alert to one viewer (§2.8).
 *
 * A push provider is a third party that will see every alert this camera ever sends, and
 * the interesting part of an alert is a thumbnail of the inside of a house. So the
 * provider gets ciphertext and a random topic name, and the plaintext never exists
 * outside the two phones.
 *
 * ECIES over P-256: an ephemeral key per message, ECDH to the recipient, HKDF-SHA256 to
 * derive an AES-256-GCM key. The ephemeral key is what gives forward secrecy — recovering
 * the recipient's long-term key later does not decrypt alerts already sent, because the
 * other half of every past exchange was thrown away.
 *
 * The recipient's public key is bound into the key derivation, so a sealed message cannot
 * be replayed at a different recipient even by someone who can pick the ephemeral key.
 */
object AlertCrypto {

    private const val CURVE = "secp256r1"
    private const val KEY_ALGORITHM = "EC"
    private const val AGREEMENT = "ECDH"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val MAC = "HmacSHA256"
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32
    private val INFO = "homecam-alert-v1".toByteArray()

    fun generateAgreementKeyPair(random: SecureRandom = SecureRandom()) =
        KeyPairGenerator.getInstance(KEY_ALGORITHM).apply {
            initialize(ECGenParameterSpec(CURVE), random)
        }.generateKeyPair()

    /**
     * Rejects a key whose point is not on P-256 itself rather than trusting the provider to
     * notice at `doPhase`: an invalid-curve point is how ECDH leaks a private key a few bits
     * at a time. Throws [GeneralSecurityException] so every caller's existing failure path applies.
     */
    fun publicKeyFrom(spki: ByteArray): PublicKey {
        val key = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(spki))
        val ec = key as? ECPublicKey ?: throw InvalidKeyException("not an EC key")
        if (!onCurve(ec.w.affineX, ec.w.affineY, ec.params)) throw InvalidKeyException("point is not on the curve")
        return key
    }

    /** True when (x, y) is a finite point on the P-256 curve [params] describes. */
    internal fun onCurve(x: BigInteger, y: BigInteger, params: ECParameterSpec): Boolean {
        val curve = params.curve
        val field = curve.field as? ECFieldFp ?: return false
        val p = field.p
        if (curve.a != P256_A) return false
        if (curve.b != P256_B) return false
        if (p != P256_P) return false
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val left = y.modPow(BigInteger.TWO, p)
        val right = (x.modPow(BigInteger.valueOf(3), p) + curve.a.multiply(x) + curve.b).mod(p)
        return left == right
    }

    private val P256_P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val P256_A = P256_P - BigInteger.valueOf(3)
    private val P256_B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    /**
     * Wire format: `version || ephemeralSpkiLength(2) || ephemeralSpki || nonce ||
     * ciphertextWithTag`. Length-prefixed so the layout stays unambiguous if the key
     * encoding ever changes size.
     */
    fun seal(
        recipientSpki: ByteArray,
        plaintext: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val recipient = publicKeyFrom(recipientSpki)
        val ephemeral = generateAgreementKeyPair(random)
        val ephemeralSpki = ephemeral.public.encoded

        val key = deriveKey(agree(ephemeral.private, recipient), ephemeralSpki, recipientSpki)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header(ephemeralSpki))
        }
        val sealed = cipher.doFinal(plaintext)

        return header(ephemeralSpki) + nonce + sealed
    }

    /** Null on any failure: a message that does not open is not an error to report to the sender. */
    fun open(recipientPrivateKey: PrivateKey, recipientSpki: ByteArray, sealed: ByteArray): ByteArray? = try {
        require(sealed.size > 3 && sealed[0] == VERSION) { "unrecognised alert envelope" }
        val ephemeralLength = ((sealed[1].toInt() and 0xFF) shl 8) or (sealed[2].toInt() and 0xFF)
        require(sealed.size > 3 + ephemeralLength + NONCE_BYTES) { "truncated alert" }

        val ephemeralSpki = sealed.copyOfRange(3, 3 + ephemeralLength)
        val nonce = sealed.copyOfRange(3 + ephemeralLength, 3 + ephemeralLength + NONCE_BYTES)
        val body = sealed.copyOfRange(3 + ephemeralLength + NONCE_BYTES, sealed.size)

        val key = deriveKey(
            agree(recipientPrivateKey, publicKeyFrom(ephemeralSpki)),
            ephemeralSpki,
            recipientSpki,
        )
        Cipher.getInstance(CIPHER).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header(ephemeralSpki))
        }.doFinal(body)
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun header(ephemeralSpki: ByteArray) = byteArrayOf(
        VERSION,
        (ephemeralSpki.size ushr 8).toByte(),
        ephemeralSpki.size.toByte(),
    ) + ephemeralSpki

    private fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance(AGREEMENT).run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    /** HKDF-SHA256, extract then expand, over one output block. */
    private fun deriveKey(shared: ByteArray, ephemeralSpki: ByteArray, recipientSpki: ByteArray): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest(Canonical.encode(ephemeralSpki, recipientSpki))
        val prk = hmac(salt, shared)
        return hmac(prk, INFO + byteArrayOf(1)).copyOf(KEY_BYTES)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(MAC).run {
            init(SecretKeySpec(key, MAC))
            doFinal(data)
        }
}
