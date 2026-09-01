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
import com.homecam.app.alerts.Alerts
import com.homecam.app.data.ClipStore
import com.homecam.app.detect.DetectionPipeline
import com.homecam.app.media.CameraMediaSession
import com.homecam.app.media.ContinuousCapture
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private lateinit var capture: ContinuousCapture
    private lateinit var recorder: Recorder
    private lateinit var detection: DetectionPipeline
    private lateinit var alerts: Alerts

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

        capture = ContinuousCapture(this, this).also {
            it.start(mask = graph.settings.current.privacyMask)
        }
        recorder = Recorder(graph, capture, ClipStore(this, graph.database.clips())).also {
            it.start()
            it.setArmed(graph.settings.current.armed)
        }
        detection = DetectionPipeline(
            context = this,
            settings = { graph.settings.current },
            onEvent = { detections, thumbnailJpeg ->
                recorder.onDetection(detections, System.currentTimeMillis())
                if (detections.isNotEmpty()) {
                    alerts.onDetection(detections.map { it.category }, clipId = "", thumbnailJpeg = thumbnailJpeg)
                }
            },
        ).also { capture.addSink(it) }
        startClipRollover()

        server = SignalingServer(graph, node).also {
            it.listener = this
            it.mediaController = this
            it.clipStore = recorder.clips
            it.start()
        }
        alerts = Alerts(graph)
        ClipPruneWorker.schedule(this)
        observeArming()
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
        capture.removeSink(detection)
        detection.close()
        recorder.stop()
        capture.stop()
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

    /** Closes a clip once nothing has been detected for its tail duration. */
    private fun startClipRollover() {
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(ROLLOVER_TICK_MS)
                recorder.tick()
                statusFromPipeline()
            }
        }
    }

    private fun statusFromPipeline() {
        server?.statusProvider?.detectorFps = detection.framesPerSecond
    }

    /**
     * Arming is a setting a viewer can change over RPC, so the recorder follows the
     * setting rather than the call site that happened to change it.
     */
    private fun observeArming() {
        lifecycleScope.launch {
            graph.settings.settings.map { it.armed }.distinctUntilChanged().collect {
                recorder.setArmed(it)
            }
        }
    }

    // ------------------------------------------------------- MediaController

    override suspend fun startSession(viewerFingerprint: String, config: SessionConfig): SessionStatus {
        val peer = server?.sessionOf(viewerFingerprint)
            ?: throw IllegalStateException("no authenticated connection for $viewerFingerprint")

        endLive(Bye.Reason.SESSION_REPLACED, "replaced by $viewerFingerprint")

        val media = CameraMediaSession(
            context = this,
            capture = capture,
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
        alerts.onSessionStarted(peer.label, viewerFingerprint)

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

        val stats = kotlinx.coroutines.withTimeoutOrNull(STATS_TIMEOUT_MS) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                current.media.stats { continuation.resume(it) { _, _, _ -> } }
            }
        } ?: CameraMediaSession.Stats()

        return SessionStatus.newBuilder()
            .setSessionId(current.media.id)
            .setActive(true)
            .setStartedAtMs(current.startedAtMs)
            .setVideoWidth(stats.width.takeIf { it > 0 } ?: capture.format.width)
            .setVideoHeight(stats.height.takeIf { it > 0 } ?: capture.format.height)
            .setBitrateKbps(stats.bitrateKbps)
            .setRttMs(stats.rttMs)
            .setPacketsLost(stats.packetsLost)
            .setOrientationDegrees(capture.orientationDegrees)
            .build()
    }

    override fun setTorch(on: Boolean) = capture.setTorch(on) ?: Unit

    override fun setZoom(ratio: Float) = capture.setZoom(ratio) ?: Unit

    override fun setPushToTalk(active: Boolean) = Unit

    override suspend fun setResolution(resolution: com.homecam.proto.Resolution): SessionStatus {
        capture.setFormat(
            ContinuousCapture.Format(
                width = resolution.width.coerceIn(320, 1920),
                height = resolution.height.coerceIn(240, 1080),
                framerate = resolution.fps.coerceIn(5, 30),
            )
        )
        return sessionStatus(live?.peer?.viewer?.shortFingerprint.orEmpty())
    }

    override fun setLens(facing: com.homecam.proto.Lens.Facing) = capture.setLens(facing) ?: Unit

    override fun setOrientation(degrees: Int) = capture.setOrientation(degrees)

    override suspend fun snapshot(): com.homecam.proto.Snapshot {
        val jpeg = capture.snapshotJpeg()
            ?: throw com.homecam.core.rpc.RpcException(
                com.homecam.proto.RpcError.Code.UNAVAILABLE, "capture is not running",
            )
        return com.homecam.proto.Snapshot.newBuilder()
            .setJpeg(com.google.protobuf.ByteString.copyFrom(jpeg))
            .setWidth(capture.format.width)
            .setHeight(capture.format.height)
            .build()
    }

    override fun recordManually(seconds: Int, start: Boolean) {
        if (start) recorder.recordManually(seconds) else recorder.stopManual()
    }

    /**
     * A mask edited while somebody is watching has to take effect on that stream, not the
     * next one — the whole point is that the pixels never leave.
     */
    override fun setPrivacyMask(mask: com.homecam.proto.PrivacyMask) = capture.setPrivacyMask(mask)

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
        private const val CHANNEL_ID = "homecam.camera"
        private const val ROLLOVER_TICK_MS = 2_000L
        private const val STATS_TIMEOUT_MS = 2_000L
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
