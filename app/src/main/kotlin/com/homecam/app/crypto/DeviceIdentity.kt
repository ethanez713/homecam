package com.homecam.app.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyInfo
import android.util.Log
import com.homecam.core.crypto.IdentityKey
import com.homecam.core.crypto.IdentitySigner
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * This device's long-lived identity (§2.2). The private key is generated inside the
 * Android Keystore — StrongBox when the device has one — and is never exported, backed
 * up or displayed; this class only ever holds a handle to it.
 *
 * The self-signed certificate the Keystore issues alongside the key *is* the certificate
 * the camera serves over TLS, so "a certificate whose key is bound to the identity key"
 * is an identity rather than a claim that has to be checked.
 */
class DeviceIdentity private constructor(
    private val privateKey: PrivateKey,
    val certificate: X509Certificate,
) : IdentitySigner {

    /** Shown on the security screen so "hardware-backed" is a fact the user can check. */
    val hardwareBacked: Boolean = isInsideSecureHardware(privateKey)

    override val identity = IdentityKey(certificate.publicKey.encoded)

    val certificateDer: ByteArray get() = certificate.encoded

    /** What the viewer pins at pairing time and checks on every connection after it. */
    val certificateFingerprint: ByteArray
        get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

    fun privateKeyHandle(): PrivateKey = privateKey

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(IdentityKey.SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }

    companion object {
        private const val TAG = "DeviceIdentity"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "homecam.identity.v1"

        @Volatile private var cached: DeviceIdentity? = null

        fun get(): DeviceIdentity = cached ?: synchronized(this) {
            cached ?: load().also { cached = it }
        }

        private fun load(): DeviceIdentity {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (existing != null) {
                return DeviceIdentity(existing.privateKey, existing.certificate as X509Certificate)
            }
            return generate(strongBox = true)
                ?: generate(strongBox = false)
                ?: error("the Keystore refused to generate an identity key")
        }

        private fun isInsideSecureHardware(key: PrivateKey): Boolean = try {
            val info = java.security.KeyFactory
                .getInstance(key.algorithm, KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware
            }
        } catch (e: java.security.GeneralSecurityException) {
            false
        }

        private fun generate(strongBox: Boolean): DeviceIdentity? {
            if (strongBox && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            val notBefore = Calendar.getInstance()
            val notAfter = (notBefore.clone() as Calendar).apply { add(Calendar.YEAR, 30) }

            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(IdentityKey.CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                // No user-authentication requirement: the camera has to be able to answer
                // a paired viewer while the phone sits locked above the front door.
                .setCertificateSubject(X500Principal("CN=homecam"))
                .setCertificateSerialNumber(BigInteger.valueOf(1))
                .setCertificateNotBefore(notBefore.time)
                .setCertificateNotAfter(notAfter.time)
                .apply {
                    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()

            return try {
                val generator = java.security.KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, KEYSTORE,
                )
                generator.initialize(spec)
                generator.generateKeyPair()

                val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
                DeviceIdentity(entry.privateKey, entry.certificate as X509Certificate)
            } catch (e: java.security.ProviderException) {
                // StrongBoxUnavailableException extends ProviderException, but several
                // vendors throw the bare superclass when StrongBox is present and refuses.
                if (!strongBox) throw e
                Log.i(TAG, "StrongBox declined the key; falling back to the TEE", e)
                null
            }
        }
    }
}
