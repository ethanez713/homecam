package com.homecam.app.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.homecam.app.AppGraph
import com.homecam.app.MainActivity
import com.homecam.app.R
import com.homecam.core.crypto.AlertCrypto
import com.homecam.proto.AlertPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Holds the viewer's connection to its alert topic and decrypts what arrives.
 *
 * Decryption happens here rather than anywhere else in the system: the push provider, the
 * network and the notification shade all only ever see ciphertext until this service opens
 * it with a key that never left this phone (§2.8).
 *
 * Off unless the user turned alerts on, and it says plainly in its own notification that
 * it is running — a background service watching a network connection should not be
 * invisible.
 */
class AlertSubscriber : LifecycleService() {

    private lateinit var graph: AppGraph
    private lateinit var keys: AlertKeys

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        keys = AlertKeys(this)
        createChannels()
        startForeground(NOTIFICATION_ID, statusNotification())
        lifecycleScope.launch { listen() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private suspend fun listen() {
        val endpoint = endpointOf(this).trimEnd('/')
        val topic = keys.topic(this)
        var backoffMs = MIN_BACKOFF_MS

        while (true) {
            val clean = runCatching { stream(endpoint, topic) }
                .onFailure { Log.i(TAG, "alert stream dropped", it) }
                .isSuccess
            // A stream that ends cleanly is a server-side idle timeout and should be
            // reconnected immediately; a failure is a network problem and should not be
            // retried in a tight loop.
            backoffMs = if (clean) MIN_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    private suspend fun stream(endpoint: String, topic: String) = withContext(Dispatchers.IO) {
        val connection = (URL("$endpoint/$topic/json").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "homecam")
        }
        try {
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (line.isNotBlank()) handle(line) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun handle(line: String) {
        val message = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (message.optString("event") != "message") return
        val body = message.optString("message").takeIf { it.isNotBlank() } ?: return

        logDeliveryDelay(message)

        val sealed = runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrNull()
        val opened = sealed?.let { AlertCrypto.open(keys.privateKey, keys.publicKeySpki, it) }
        if (opened == null) {
            // Either the content-free fallback ping, or something we cannot read. Both
            // deserve the same non-committal nudge rather than a guess at the contents.
            notify("homecam", "Check your camera")
            return
        }

        val payload = runCatching { AlertPayload.parseFrom(opened) }.getOrNull() ?: return
        notify(
            title = payload.cameraAlias.ifBlank { "homecam" },
            body = describe(payload.labelsList),
            thumbnail = payload.thumbnailJpeg.takeIf { !it.isEmpty }?.toByteArray(),
        )
    }

    private fun describe(labels: List<String>): String = when {
        labels.isEmpty() -> "Something happened"
        labels.first() == SESSION_LABEL -> labels.getOrNull(1)?.let { "$it started watching" } ?: "Someone started watching"
        else -> "Detected: ${labels.joinToString(", ")}"
    }

    /**
     * How long after the push server had the message this phone actually surfaced it —
     * the Doze delay GATE-F point 2 is looking for. The server's own `time` is the
     * reference so the two phones' clocks need not agree.
     */
    private fun logDeliveryDelay(message: JSONObject) {
        val serverTimeMs = message.optLong("time", 0) * 1000
        if (serverTimeMs > 0) {
            Log.i(TAG, "alert surfaced ${System.currentTimeMillis() - serverTimeMs}ms after the server had it")
        }
    }

    private fun notify(title: String, body: String, thumbnail: ByteArray? = null) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ALERT_CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        builder.setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentIntent(open)
            .setAutoCancel(true)

        thumbnail?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                builder.setStyle(Notification.BigPictureStyle().bigPicture(it))
            }
        }

        getSystemService(NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun statusNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, STATUS_CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Alerts on")
            .setContentText("Listening for encrypted alerts from your cameras")
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setOngoing(true)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, "Alert listener", NotificationManager.IMPORTANCE_MIN)
        )
    }

    companion object {
        private const val TAG = "AlertSubscriber"
        const val SESSION_LABEL = "session"
        private const val ALERT_CHANNEL = "homecam.alerts"
        private const val STATUS_CHANNEL = "homecam.alerts.status"
        private const val NOTIFICATION_ID = 2
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 0
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 120_000L

        private const val PREFS = "homecam.alerts"
        private const val KEY_ENDPOINT = "endpoint"

        /** Defaults to ntfy.sh, which needs no account and accepts an arbitrary topic. */
        const val DEFAULT_ENDPOINT = "https://ntfy.sh"

        fun endpointOf(context: Context): String = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT

        fun setEndpoint(context: Context, endpoint: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENDPOINT, endpoint).apply()
        }

        fun start(context: Context) {
            val intent = Intent(context, AlertSubscriber::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) = context.stopService(Intent(context, AlertSubscriber::class.java))
    }
}
