package com.homecam.app.alerts

import android.util.Log
import com.homecam.core.crypto.AlertReserve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * How many messages the push server will still take from this address today. ntfy answers
 * `GET /v1/account` anonymously with its own counter, and the request does not spend it.
 */
object AlertQuota {

    /** Null whenever the answer cannot be trusted, so the caller sends rather than guesses. */
    suspend fun remaining(endpoint: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("${endpoint.trimEnd('/')}/v1/account").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val account = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                AlertReserve.remaining(
                    limitMessages = account.getJSONObject("limits").getInt("messages"),
                    messagesRemaining = account.getJSONObject("stats").getInt("messages_remaining"),
                )
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.i(TAG, "alert budget unreadable: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private const val TAG = "AlertQuota"
    private const val TIMEOUT_MS = 5_000
}
