package com.homecam.core.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * An in-memory identity. On device the private key lives in the Android Keystore and this
 * class is not used; it exists so the handshake and pairing state machines can be driven
 * end to end in JVM unit tests with no emulator.
 */
class JvmIdentitySigner(private val keyPair: KeyPair) : IdentitySigner {

    override val identity = IdentityKey(keyPair.public.encoded)

    val privateKey: PrivateKey get() = keyPair.private

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(IdentityKey.SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(data)
            sign()
        }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): JvmIdentitySigner =
            JvmIdentitySigner(
                KeyPairGenerator.getInstance(IdentityKey.KEY_ALGORITHM).apply {
                    initialize(ECGenParameterSpec(IdentityKey.CURVE), random)
                }.generateKeyPair()
            )
    }
}
