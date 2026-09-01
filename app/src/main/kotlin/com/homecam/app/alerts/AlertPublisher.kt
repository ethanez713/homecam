package com.homecam.app.alerts

import android.util.Base64
import android.util.Log
import com.homecam.app.data.ViewerEntity
import com.homecam.core.crypto.AlertCrypto
import com.homecam.core.crypto.AlertLimits
import com.homecam.proto.AlertPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts a sealed alert to a viewer's push topic (§2.8).
 *
 * The provider receives ciphertext and a random topic name. It never learns the camera's
 * name, the label that fired, or the thumbnail. When encryption or delivery fails the
 * fallback is a **content-free** ping — "check your camera" — rather than the plaintext,
 * because a degraded path that leaks is worse than a degraded path that says less.
 */
class AlertPublisher(private val userAgent: String = "homecam") {

    suspend fun publish(viewer: ViewerEntity, payload: AlertPayload): Boolean {
        val endpoint = viewer.alertEndpoint.trimEnd('/')
        val key = viewer.alertPublicKey
        if (!viewer.alertsEnabled || endpoint.isBlank() || viewer.alertTopic.isBlank()) return false

        val body = if (key == null) null else sealWithinLimit(key, payload)

        return if (body == null) {
            post(endpoint, viewer.alertTopic, CONTENT_FREE_PING.toByteArray(), plain = true)
        } else {
            post(endpoint, viewer.alertTopic, body, plain = false)
        }
    }

    /**
     * Seals the payload to a Base64 body under the push-provider limit, dropping the
     * thumbnail first if the full payload does not fit. Returns null when even the
     * thumbnail-free payload will not seal — the caller then falls back to a content-free
     * ping rather than posting anything readable.
     */
    private fun sealWithinLimit(key: ByteArray, payload: AlertPayload): ByteArray? {
        val candidates = sequenceOf(payload, payload.toBuilder().clearThumbnailJpeg().build())
        for (candidate in candidates) {
            val sealed = runCatching { AlertCrypto.seal(key, candidate.toByteArray()) }.getOrNull()
                ?: continue
            val body = Base64.encode(sealed, Base64.NO_WRAP)
            if (body.size <= AlertLimits.MAX_SEALED_BODY_BYTES) return body
        }
        return null
    }

    private suspend fun post(
        endpoint: String,
        topic: String,
        body: ByteArray,
        plain: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("$endpoint/$topic").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Content-Type", "text/plain")
                // The provider shows the title in the notification shade, so it must not
                // say anything either: the real content is inside the ciphertext.
                setRequestProperty("Title", "homecam")
                if (!plain) setRequestProperty("X-Homecam", "1")
            }
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        }.getOrElse {
            Log.w(TAG, "alert delivery failed", it)
            false
        }
    }

    private companion object {
        const val TAG = "AlertPublisher"
        const val TIMEOUT_MS = 10_000
        const val CONTENT_FREE_PING = "check your camera"
    }
}
