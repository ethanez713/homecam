package com.homecam.app.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.homecam.app.AppGraph
import com.homecam.app.MainActivity
import com.homecam.app.R
import com.homecam.core.crypto.AlertCrypto
import com.homecam.core.crypto.AlertEndpoint
import com.homecam.core.diagnostics.AlertKind
import com.homecam.core.diagnostics.DiagnosticsLines
import com.homecam.core.health.HealthAlertLabels
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

        var reconnecting = false
        while (true) {
            if (reconnecting) graph.viewerDiagnostics.reconnect(backoffMs)
            reconnecting = true
            val clean = runCatching { stream(endpoint, topic) }
                .onFailure { Log.i(TAG, "alert stream dropped", it) }
                .isSuccess
            // A stream that ends cleanly is a server-side idle timeout and should be
            // reconnected immediately; a failure is a network problem and should not be
            // retried in a tight loop.
            backoffMs = if (clean) MIN_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            if (!clean) graph.viewerDiagnostics.streamDrop(backoffMs)
            delay(backoffMs)
        }
    }

    private suspend fun stream(endpoint: String, topic: String) = withContext(Dispatchers.IO) {
        val connection = (URL("$endpoint/$topic/json").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
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

        val repeat = seenBefore(message.optString("id"), body)
        val sealed = runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrNull()
        val opened = sealed?.let { AlertCrypto.open(keys.privateKey, keys.publicKeySpki, it) }
        if (opened == null) {
            // Either the content-free fallback ping, or something we cannot read. Both
            // deserve the same non-committal nudge rather than a guess at the contents.
            record(message, repeat, DiagnosticsLines.alertKind(opened = false, firstLabel = null))
            if (!repeat) notify("homecam", "Check your camera")
            return
        }

        val payload = runCatching { AlertPayload.parseFrom(opened) }.getOrNull() ?: return
        val kind = DiagnosticsLines.alertKind(opened = true, firstLabel = payload.labelsList.firstOrNull())
        record(message, repeat, kind)
        if (repeat) return
        val opensLive = kind == AlertKind.SESSION || kind == AlertKind.HEALTH
        val alias = payload.cameraAlias.ifBlank { "homecam" }
        notify(
            title = if (payload.urgent) "Urgent — $alias" else alias,
            urgent = payload.urgent,
            body = describe(payload.labelsList),
            camera = payload.cameraFingerprint,
            // A detection opens on the recording of it; a session opens on the live view.
            atMs = payload.atMs.takeIf { !opensLive } ?: 0,
            thumbnail = payload.thumbnailJpeg.takeIf { !it.isEmpty }?.toByteArray(),
        )
    }

    /**
     * Each alert was logged twice. A message that arrives twice with the same id is a stream
     * replay; the same sealed body under a new id is the publisher's POST having been retried.
     * Both are shown once, and the line says which it was, so the next occurrence names its cause.
     */
    private fun seenBefore(id: String, body: String): Boolean {
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > DUPLICATE_WINDOW_MS }
        val bodyKey = "body:" + body.hashCode()
        val idKey = "id:$id"
        val repeated = when {
            id.isNotBlank() && idKey in recent -> "same id"
            bodyKey in recent -> "same body, new id"
            else -> null
        }
        if (id.isNotBlank()) recent[idKey] = now
        recent[bodyKey] = now
        if (repeated != null) Log.i(TAG, "dropped a repeated alert ($repeated)")
        return repeated != null
    }

    private val recent = HashMap<String, Long>()

    private fun describe(labels: List<String>): String = when {
        labels.isEmpty() -> "Something happened"
        HealthAlertLabels.isHealth(labels.first()) -> HealthAlertLabels.describe(labels.first()) ?: "The camera's health changed"
        labels.first() == SESSION_LABEL -> labels.getOrNull(1)?.let { "$it started watching" } ?: "Someone started watching"
        else -> "Detected: " + labels.groupingBy { it }.eachCount().entries
            .joinToString(", ") { (label, count) -> if (count > 1) "$label ×$count" else label }
    }

    /**
     * How long after the push server had the message this phone actually surfaced it —
     * the Doze delay GATE-F point 2 is looking for. The server's own `time` is the
     * reference so the two phones' clocks need not agree.
     */
    private fun record(message: JSONObject, repeat: Boolean, kind: AlertKind) {
        val delayMs = DiagnosticsLines.delayMs(message.optLong("time", 0), System.currentTimeMillis())
        graph.viewerDiagnostics.alert(delayMs, repeat, kind, idleNow())
        if (!repeat && delayMs != null) {
            Log.i(TAG, "alert surfaced ${delayMs}ms after the server had it")
        }
    }

    /** Doze, or the screen off. Sampled at arrival, which is the moment a delay has to explain. */
    private fun idleNow(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return false
        return power.isDeviceIdleMode || !power.isInteractive
    }

    private fun notify(
        title: String,
        body: String,
        thumbnail: ByteArray? = null,
        camera: String = "",
        atMs: Long = 0,
        urgent: Boolean = false,
    ) {
        val id = System.currentTimeMillis().toInt()
        val open = PendingIntent.getActivity(
            // Each alert keeps its own extras, so the request code has to differ too.
            this, id,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CAMERA, camera)
                .putExtra(MainActivity.EXTRA_AT_MS, atMs),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, if (urgent) URGENT_CHANNEL else ALERT_CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        if (urgent) builder.setCategory(Notification.CATEGORY_ALARM)
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
            .notify(id, builder.build())
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
            NotificationChannel(URGENT_CHANNEL, "Urgent alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, "Alert listener", NotificationManager.IMPORTANCE_MIN)
        )
    }

    companion object {
        private const val TAG = "AlertSubscriber"
        const val SESSION_LABEL = DiagnosticsLines.SESSION_LABEL
        private const val ALERT_CHANNEL = "homecam.alerts"
        private const val URGENT_CHANNEL = "homecam.alerts.urgent"
        private const val STATUS_CHANNEL = "homecam.alerts.status"
        private const val NOTIFICATION_ID = 2
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 0
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val DUPLICATE_WINDOW_MS = 60_000L

        private const val PREFS = "homecam.alerts"
        private const val KEY_ENDPOINT = "endpoint"

        /** Defaults to ntfy.sh, which needs no account and accepts an arbitrary topic. */
        const val DEFAULT_ENDPOINT = "https://ntfy.sh"

        fun endpointOf(context: Context): String = AlertEndpoint.validOrNull(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT).orEmpty(),
        ) ?: DEFAULT_ENDPOINT

        /** False, and nothing stored, when the endpoint is not one the camera would accept. */
        fun setEndpoint(context: Context, endpoint: String): Boolean {
            val checked = AlertEndpoint.validOrNull(endpoint) ?: return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENDPOINT, checked).apply()
            return true
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
