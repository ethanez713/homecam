package com.homecam.core.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
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

    fun publicKeyFrom(spki: ByteArray): PublicKey =
        KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(spki))

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
