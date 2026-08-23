package com.homecam.app.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.homecam.app.AppGraph
import com.homecam.app.MainActivity
import com.homecam.app.R
import com.homecam.app.net.MdnsAdvertiser
import com.homecam.app.net.Signaling
import com.homecam.app.net.SignalingServer
import com.homecam.proto.AuditEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the camera alive across days: the signaling server, the mDNS advertisement and
 * (from M4) the capture pipeline all hang off this. It is a foreground service of type
 * `camera` rather than `dataSync` because `dataSync` is capped at a few hours a day from
 * Android 15, which would quietly kill a camera that is supposed to run for weeks.
 */
class CameraService : Service() {

    private lateinit var graph: AppGraph
    private lateinit var node: CameraNode
    private var server: SignalingServer? = null
    private var mdns: MdnsAdvertiser? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        node = graph.cameraNode
        createChannel()
        startForegroundWithNotification(idleNotification())

        server = SignalingServer(graph, node).also { it.start() }
        mdns = MdnsAdvertiser(this).also { it.start(Signaling.DEFAULT_PORT) }
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.value = false
        mdns?.stop()
        runCatching { server?.stop(SHUTDOWN_GRACE_MS) }
            .onFailure { Log.w(TAG, "signaling server did not stop cleanly", it) }
        graph.scope.launch { graph.audit.record(AuditEvent.Kind.CAMERA_STOPPED) }
        super.onDestroy()
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun idleNotification(): Notification = notification(
        title = graph.settings.current.alias,
        body = "Watching. No one is connected.",
    )

    private fun notification(title: String, body: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** §2.7: while a session is live the camera device itself says so, unmissably. */
    fun showLive(viewerLabel: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            if (viewerLabel == null) idleNotification()
            else notification(graph.settings.current.alias, "● LIVE — $viewerLabel is watching"),
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Camera",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows whether the camera is running and who is watching" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "homecam.camera"
        private const val NOTIFICATION_ID = 1
        private const val SHUTDOWN_GRACE_MS = 1_000

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) = context.stopService(Intent(context, CameraService::class.java))
    }
}
