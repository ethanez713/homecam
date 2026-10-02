package com.homecam.app.net

import com.homecam.app.crypto.DeviceIdentity
import com.homecam.core.crypto.ConstantTime
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * TLS on both ends is keyed by pinning, not by a certificate authority. There is no CA in
 * this system and nothing to enrol with; the viewer learned the exact certificate at
 * pairing time, so the only question worth asking is "is this the same certificate".
 */
object PinnedTls {

    /**
     * The camera serves its Keystore identity directly. `KeyManagerFactory.init` cannot be
     * used here — it wants to read the private key out of a `KeyStore`, and the whole
     * point of a Keystore-held key is that nothing can. Conscrypt signs the handshake
     * through the key handle instead.
     */
    fun serverContext(identity: DeviceIdentity): SSLContext {
        val chain = arrayOf(identity.certificate)
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
            override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKeyHandle()
        }
        return newContext().apply {
            init(arrayOf(keyManager), arrayOf(acceptAnyClientCertificate()), SecureRandom())
        }
    }

    /**
     * The viewer trusts exactly one public key: the camera identity it pinned at pairing
     * time (§2.2). Pinning the key rather than the whole certificate is possible only
     * because the Keystore issues the certificate *from* the identity key (D1) — there is
     * one secret behind both, so pinning both would be pinning the same thing twice.
     *
     * [onServerCertificate] receives the certificate actually served, which the caller
     * hashes into the handshake transcript as a channel binding.
     */
    fun clientContext(
        pinnedIdentitySpki: ByteArray,
        onServerCertificate: (X509Certificate) -> Unit = {},
    ): SSLContext {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("this context is never a server")

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate offered")
                if (!ConstantTime.equals(pinnedIdentitySpki, leaf.publicKey.encoded)) {
                    throw CertificateException("certificate does not match the pinned camera")
                }
                onServerCertificate(leaf)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return newContext().apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
    }

    /**
     * The camera does not authenticate viewers with TLS client certificates — it does that
     * with the signed challenge-response over the pinned channel, which binds the identity
     * key rather than a second key nobody pinned.
     */
    private fun acceptAnyClientCertificate() = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("this context is never a client")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * TLS 1.3 where the platform has it (Android 10+), which encrypts the certificate and is
     * AEAD-only, and 1.2 where it does not. Both stay behind the same key pin; the
     * context never enables anything older than 1.2.
     */
    private fun newContext(): SSLContext =
        runCatching { SSLContext.getInstance("TLSv1.3") }.getOrElse { SSLContext.getInstance("TLSv1.2") }

    private const val ALIAS = "homecam"
}
