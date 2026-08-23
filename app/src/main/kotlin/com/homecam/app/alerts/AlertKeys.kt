package com.homecam.app.alerts

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.homecam.core.crypto.AlertCrypto
import com.homecam.core.crypto.IdentityKey
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The viewer's key for receiving alerts. Separate from the identity key on purpose: the
 * identity key is `PURPOSE_SIGN` only, and a key that can do both signing and key
 * agreement is a key doing two jobs with one blast radius.
 *
 * `PURPOSE_AGREE_KEY` only arrived at API 31, so below that this falls back to a software
 * key in app-private storage. That is a real difference, and it is the right trade here:
 * the alternative is no encrypted alerts at all on an older phone, and what this key
 * protects — a thumbnail and a label — is worth much less than the identity it is
 * deliberately not.
 */
class AlertKeys(context: Context) {

    private val softwareKeyFile = File(context.filesDir, "alert-agreement.key")

    private val keyPair: Pair<PrivateKey, ByteArray> by lazy { loadOrCreate() }

    val privateKey: PrivateKey get() = keyPair.first
    val publicKeySpki: ByteArray get() = keyPair.second

    /** Random per install, so a push provider cannot correlate a topic with anything. */
    fun topic(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOPIC, null)?.let { return it }
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val topic = "hc-" + bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_TOPIC, topic).apply()
        return topic
    }

    private fun loadOrCreate(): Pair<PrivateKey, ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fromKeystore()?.let { return it }
        }
        return fromFile()
    }

    private fun fromKeystore(): Pair<PrivateKey, ByteArray>? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            existing.privateKey to existing.certificate.publicKey.encoded
        } else {
            val generator = java.security.KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, KEYSTORE,
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(IdentityKey.CURVE))
                    .build()
            )
            val pair = generator.generateKeyPair()
            pair.private to pair.public.encoded
        }
    } catch (e: Exception) {
        Log.i(TAG, "no Keystore agreement key; using a software key", e)
        null
    }

    private fun fromFile(): Pair<PrivateKey, ByteArray> {
        val factory = KeyFactory.getInstance(IdentityKey.KEY_ALGORITHM)
        if (softwareKeyFile.exists()) {
            runCatching {
                val key = factory.generatePrivate(PKCS8EncodedKeySpec(softwareKeyFile.readBytes()))
                return key to publicFromPrivateFile()
            }.onFailure { Log.w(TAG, "unreadable alert key; regenerating", it) }
        }
        val pair = AlertCrypto.generateAgreementKeyPair()
        softwareKeyFile.writeBytes(pair.private.encoded)
        File(softwareKeyFile.path + ".pub").writeBytes(pair.public.encoded)
        harden(softwareKeyFile)
        return pair.private to pair.public.encoded
    }

    private fun publicFromPrivateFile(): ByteArray = File(softwareKeyFile.path + ".pub").readBytes()

    private fun harden(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    private companion object {
        const val TAG = "AlertKeys"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "homecam.alerts.v1"
        const val PREFS = "homecam.alerts"
        const val KEY_TOPIC = "topic"
    }
}
