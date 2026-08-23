package com.homecam.app.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.lifecycle.LifecycleService
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import com.homecam.app.AppGraph
import com.homecam.app.MainActivity
import com.homecam.app.R
import com.homecam.app.net.MdnsAdvertiser
import com.homecam.app.net.Signaling
import com.homecam.app.net.SignalingServer
import com.homecam.app.media.CameraMediaSession
import com.homecam.proto.AuditEvent
import com.homecam.proto.Bye
import com.homecam.proto.Candidate
import com.homecam.proto.Sdp
import com.homecam.proto.SessionConfig
import com.homecam.proto.SessionStatus
import com.homecam.proto.SignalMessage
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
class CameraService : LifecycleService(), MediaController, SignalingServer.Listener {

    private lateinit var graph: AppGraph
    private lateinit var node: CameraNode
    private var server: SignalingServer? = null
    private var mdns: MdnsAdvertiser? = null

    /**
     * At most one media session at a time, and the newest wins. A camera that answers
     * "busy" when you pick up your own phone is a camera you stop trusting to be there;
     * the displaced viewer gets SESSION_REPLACED, which says what happened.
     */
    private var live: LiveSession? = null

    private class LiveSession(
        val peer: SignalingServer.PeerSession,
        val media: CameraMediaSession,
        val startedAtMs: Long = System.currentTimeMillis(),
    )

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        node = graph.cameraNode
        createChannel()
        startForegroundWithNotification(idleNotification())

        server = SignalingServer(graph, node).also {
            it.listener = this
            it.mediaController = this
            it.start()
        }
        mdns = MdnsAdvertiser(this).also { it.start(Signaling.DEFAULT_PORT) }
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        running.value = false
        graph.scope.launch { endLive(Bye.Reason.CAMERA_DISABLED, "camera stopped") }
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
        liveViewer.value = viewerLabel
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

    // ------------------------------------------------------- MediaController

    override suspend fun startSession(viewerFingerprint: String, config: SessionConfig): SessionStatus {
        val peer = server?.sessionOf(viewerFingerprint)
            ?: throw IllegalStateException("no authenticated connection for $viewerFingerprint")

        endLive(Bye.Reason.SESSION_REPLACED, "replaced by $viewerFingerprint")

        val media = CameraMediaSession(
            context = this,
            lifecycleOwner = this,
            config = CameraMediaSession.Config(
                audio = config.audio && graph.settings.current.microphone,
            ),
            transport = object : CameraMediaSession.Transport {
                override fun sendOffer(sdp: Sdp) =
                    peer.send(SignalMessage.newBuilder().setOffer(sdp).build())

                override fun sendCandidate(candidate: Candidate) =
                    peer.send(SignalMessage.newBuilder().setCandidate(candidate).build())

                override fun tearDown(reason: Bye.Reason, detail: String) {
                    graph.scope.launch { endLive(reason, detail) }
                }
            },
        )
        live = LiveSession(peer, media)
        media.start()
        showLive(peer.label)

        graph.audit.record(
            AuditEvent.Kind.SESSION_OPENED,
            viewerFingerprint = viewerFingerprint,
            viewerLabel = peer.label,
            sourceIp = peer.sourceIp,
            detail = "live video",
        )
        return sessionStatus(viewerFingerprint)
    }

    override suspend fun stopSession(viewerFingerprint: String) {
        if (live?.peer?.viewer?.shortFingerprint == viewerFingerprint) {
            endLive(Bye.Reason.HANGUP, "viewer ended the session")
        }
    }

    override suspend fun sessionStatus(viewerFingerprint: String): SessionStatus {
        val current = live?.takeIf { it.peer.viewer?.shortFingerprint == viewerFingerprint }
            ?: return SessionStatus.getDefaultInstance()
        return SessionStatus.newBuilder()
            .setSessionId(current.media.id)
            .setActive(true)
            .setStartedAtMs(current.startedAtMs)
            .setVideoWidth(DEFAULT_WIDTH)
            .setVideoHeight(DEFAULT_HEIGHT)
            .build()
    }

    override fun setTorch(on: Boolean) { live?.media?.setTorch(on) }

    override fun setZoom(ratio: Float) { live?.media?.setZoom(ratio) }

    override fun setPushToTalk(active: Boolean) = Unit

    // ------------------------------------------------ SignalingServer.Listener

    override fun onAuthenticated(session: SignalingServer.PeerSession) = Unit

    override fun onOffer(session: SignalingServer.PeerSession, sdp: Sdp) = Unit

    override fun onAnswer(session: SignalingServer.PeerSession, sdp: Sdp) {
        live?.takeIf { it.peer === session }?.media?.onAnswer(sdp)
    }

    override fun onCandidate(session: SignalingServer.PeerSession, candidate: Candidate) {
        live?.takeIf { it.peer === session }?.media?.onCandidate(candidate)
    }

    override fun onClosed(session: SignalingServer.PeerSession, reason: Bye.Reason) {
        if (live?.peer === session) graph.scope.launch { endLive(reason, "connection closed") }
    }

    private suspend fun endLive(reason: Bye.Reason, detail: String) {
        val ending = live ?: return
        live = null
        ending.media.close()
        showLive(null)
        if (reason == Bye.Reason.FINGERPRINT_MISMATCH) {
            // §2.5 says this one is not just a teardown: it is an event the owner is
            // told about, because it means something got between the two devices.
            graph.audit.record(
                AuditEvent.Kind.FINGERPRINT_MISMATCH,
                viewerFingerprint = ending.peer.viewer?.shortFingerprint.orEmpty(),
                viewerLabel = ending.peer.label,
                sourceIp = ending.peer.sourceIp,
                detail = detail,
            )
            ending.peer.sendBye(reason, detail)
        } else if (reason == Bye.Reason.SESSION_REPLACED) {
            ending.peer.sendBye(reason, detail)
        }
        graph.audit.record(
            AuditEvent.Kind.SESSION_CLOSED,
            viewerFingerprint = ending.peer.viewer?.shortFingerprint.orEmpty(),
            viewerLabel = ending.peer.label,
            sourceIp = ending.peer.sourceIp,
            detail = "${(System.currentTimeMillis() - ending.startedAtMs) / 1000}s · $detail",
        )
    }

    companion object {
        private const val TAG = "CameraService"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val CHANNEL_ID = "homecam.camera"
        private const val NOTIFICATION_ID = 1
        private const val SHUTDOWN_GRACE_MS = 1_000

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        private val liveViewer = MutableStateFlow<String?>(null)

        /** §2.7: the camera device says on its own screen when somebody is watching. */
        val liveViewerLabel: StateFlow<String?> = liveViewer.asStateFlow()

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
