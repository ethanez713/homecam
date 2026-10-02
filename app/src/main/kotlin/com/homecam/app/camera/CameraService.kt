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
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.homecam.app.AppGraph
import com.homecam.app.BootReceiver
import com.homecam.app.MainActivity
import com.homecam.app.R
import com.homecam.app.net.MdnsAdvertiser
import com.homecam.app.net.Signaling
import com.homecam.app.net.SignalingServer
import com.homecam.app.alerts.Alerts
import com.homecam.app.data.ClipStore
import com.homecam.app.detect.AudioDetectionPipeline
import com.homecam.app.detect.DetectionPipeline
import com.homecam.app.media.CameraMediaSession
import com.homecam.app.media.CameraXVideoCapturer
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.WebRtc
import com.homecam.core.media.CaptureWatchdog
import com.homecam.core.rpc.Controls
import com.homecam.proto.AuditEvent
import com.homecam.proto.Bye
import com.homecam.proto.Candidate
import com.homecam.proto.Sdp
import com.homecam.proto.SessionConfig
import com.homecam.proto.RpcMessage
import com.homecam.proto.SessionStatus
import com.homecam.proto.SignalMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
    private lateinit var audioDetection: AudioDetectionPipeline
    private lateinit var alerts: Alerts
    private val siren by lazy { Siren(this) }
    @Volatile private var spotlightOffAtMs = 0L
    @Volatile private var autoSpotlightOn = false
    @Volatile private var lastReminderMs = 0L
    private val watchdog = CaptureWatchdog()
    private var lastResumeAtMs: Long? = null

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
        graph.diagnostics.start(startReason)
        startReason = "restart"
        createChannel()
        startForegroundWithNotification(idleNotification())

        capture = ContinuousCapture(this, this).also {
            it.setOrientation(capturePrefs().getInt(KEY_ORIENTATION, 0))
            it.start(mask = graph.settings.current.privacyMask)
        }
        recorder = Recorder(graph, capture, ClipStore(this, graph.database.clips())).also {
            it.start()
            it.setArmed(graph.settings.current.armed)
            it.setMode(graph.settings.current.recordingMode)
            it.onManualResult = ::onManualRecordResult
        }
        detection = DetectionPipeline(
            context = this,
            settings = { graph.settings.current },
            onEvent = { detections, thumbnailJpeg ->
                recorder.onDetection(detections, System.currentTimeMillis())
                capture.setPowerSaving(false)
                if (detections.isNotEmpty()) {
                    alerts.onDetection(detections.map { it.category }, clipId = "", thumbnailJpeg = thumbnailJpeg)
                    maybeAutoSpotlight()
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
        audioDetection = AudioDetectionPipeline(
            context = this,
            settings = { graph.settings.current },
            onEvent = { category, levelDb ->
                recorder.onSoundEvent(category, System.currentTimeMillis())
                alerts.onDetection(listOf(category), clipId = "", thumbnailJpeg = null)
                graph.diagnostics.soundEvent(category, levelDb)
            },
        ).also { it.sync() }
        WebRtc.capturedAudio = { audioDetection.onLiveSamples(it) }
        ClipPruneWorker.schedule(this)
        observeArming()
        observeRecordingMode()
        observeSoundSettings()
        mdns = MdnsAdvertiser(this).also { it.start(Signaling.DEFAULT_PORT) }
        running.value = true
        watchFrames()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_RESUME_CAPTURE) {
            val done = whenResumed.also { whenResumed = null }
            if (watchdog.isBlind) {
                resumeCapture(done ?: {})
            } else {
                // startForegroundService still obliges a startForeground call.
                startForegroundWithNotification(currentForegroundNotification())
                done?.invoke()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.value = false
        blind.value = false
        cancelCameraPausedNotification()
        graph.diagnostics.stop("destroy")
        // Before the server stops, or the viewer only ever sees the socket drop.
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_GRACE_MS.toLong()) { endLive(Bye.Reason.CAMERA_DISABLED, "camera stopped") }
        }
        mdns?.stop()
        siren.stop()
        WebRtc.capturedAudio = null
        audioDetection.close()
        capture.removeSink(detection)
        capture.stop()
        detection.close()
        recorder.stop()
        runCatching { server?.stop(SHUTDOWN_GRACE_MS) }
            .onFailure { Log.w(TAG, "signaling server did not stop cleanly", it) }
        graph.scope.launch { graph.audit.record(AuditEvent.Kind.CAMERA_STOPPED) }
        super.onDestroy()
    }

    /**
     * Both types, or Android silences the microphone whenever the app is not on screen:
     * sound detection hears zeros and a viewer hears nothing. Whether the microphone is
     * actually opened is still the owner's setting.
     */
    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        val paused = NotificationChannel(
            PAUSED_CHANNEL_ID,
            "Camera paused",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Shown when the camera has stopped producing frames" }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(paused)
    }

    /** Closes a clip once nothing has been detected for its tail duration. */
    private fun startClipRollover() {
        lifecycleScope.launch {
            var ticks = 0
            while (true) {
                kotlinx.coroutines.delay(ROLLOVER_TICK_MS)
                recorder.tick()
                capture.setPowerSaving(live == null && !recorder.eventInProgress)
                statusFromPipeline()
                maintainResponses()
                // Blindness is fifteen seconds without a frame, so the rollover tick
                // already wakes often enough to notice it.
                watchFrames()
                if (ticks % HEARTBEAT_EVERY_TICKS == 0) emitHeartbeat()
                ticks++
            }
        }
    }

    /** A periodic health line so a soak failure can be correlated with the state at the time. */
    private fun emitHeartbeat() {
        graph.scope.launch {
            val clipBytes = graph.database.clips().all().sumOf { it.sizeBytes }
            graph.diagnostics.heartbeat(
                status = server?.statusProvider?.snapshot(clipBytes) ?: return@launch,
                recording = recorder.recording,
                mode = graph.settings.current.recordingMode.name,
            )
        }
    }

    private fun statusFromPipeline() {
        server?.statusProvider?.detectorFps = detection.framesPerSecond
    }

    private fun watchFrames() {
        val observation = watchdog.observe(
            CaptureWatchdog.Sample(
                nowMs = SystemClock.elapsedRealtime(),
                running = capture.running,
                frameCount = capture.frameCount,
            ),
        )
        server?.statusProvider?.captureFps = observation.framesPerSecond
        when (observation.signal) {
            CaptureWatchdog.Signal.BECAME_BLIND -> onCaptureBlind()
            CaptureWatchdog.Signal.ESCALATE -> showCameraPausedNotification()
            CaptureWatchdog.Signal.RECOVERED -> onCaptureRecovered()
            null -> Unit
        }
    }

    private fun onCaptureBlind() {
        blind.value = true
        val code = capture.lastCameraErrorCode
        graph.diagnostics.cameraBlind(code, code?.let(CameraXVideoCapturer::errorName))
        // A window would win the camera back from an app the owner opened on purpose, so
        // that case gets only the notification.
        if (Settings.canDrawOverlays(this) && !CameraXVideoCapturer.isHeldByAnotherApp(code)) {
            val opened = runCatching { startActivity(ResumeCameraActivity.intent(this)) }
                .onFailure { Log.w(TAG, "could not open the resume activity", it) }
                .isSuccess
            if (opened) {
                watchdog.noteRecoveryAttempt(SystemClock.elapsedRealtime())
                return
            }
        }
        showCameraPausedNotification()
    }

    private fun onCaptureRecovered() {
        blind.value = false
        cancelCameraPausedNotification()
        graph.diagnostics.cameraRecovered()
    }

    /**
     * Re-enters the foreground while a window is up, which is what makes Android
     * re-evaluate while-in-use camera access, then restarts capture. The mask processor
     * — and every sink on it — survives the restart; only the capturer is new.
     *
     * The resume activity and the home screen both ask, and the second ask arrives
     * before a frame from the first restart can exist, so a second one inside
     * [RESUME_COOLDOWN_MS] is ignored.
     */
    private fun resumeCapture(done: () -> Unit) {
        if (!::capture.isInitialized || !watchdog.isBlind) {
            done()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = lastResumeAtMs
        if (last != null && now - last < RESUME_COOLDOWN_MS) {
            done()
            return
        }
        lastResumeAtMs = now
        val entered = runCatching { startForegroundWithNotification(currentForegroundNotification()) }
        if (entered.isFailure) {
            Log.w(TAG, "could not re-enter the foreground to resume capture", entered.exceptionOrNull())
            showCameraPausedNotification()
            done()
            return
        }
        lifecycleScope.launch {
            try {
                endLive(Bye.Reason.CAMERA_DISABLED, "restarting capture")
                capture.stop()
                capture.start(mask = graph.settings.current.privacyMask)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "capture did not resume", e)
                graph.diagnostics.error("resume_capture", e.message)
                showCameraPausedNotification()
            } finally {
                done()
            }
        }
    }

    private fun currentForegroundNotification(): Notification {
        val viewer = liveViewer.value
        return if (viewer == null) idleNotification()
        else notification(graph.settings.current.alias, "● LIVE — $viewer is watching")
    }

    private fun showCameraPausedNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(PAUSED_NOTIFICATION_ID, cameraPausedNotification())
    }

    private fun cancelCameraPausedNotification() {
        getSystemService(NotificationManager::class.java).cancel(PAUSED_NOTIFICATION_ID)
    }

    private fun cameraPausedNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            PAUSED_NOTIFICATION_ID,
            ResumeCameraActivity.intent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, PAUSED_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
        }
        return builder
            .setContentTitle("Camera paused — tap to resume")
            .setContentText("The camera stopped producing frames.")
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_ERROR)
            .setOngoing(true)
            .build()
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

    private fun observeRecordingMode() {
        lifecycleScope.launch {
            graph.settings.settings.map { it.recordingMode }.distinctUntilChanged().collect {
                recorder.setMode(it)
            }
        }
    }

    private fun observeSoundSettings() {
        lifecycleScope.launch {
            graph.settings.settings
                .map { it.microphone to it.soundDetection.enabled }
                .distinctUntilChanged()
                .collect { audioDetection.sync() }
        }
    }

    /**
     * A manual clip closed. Tell the viewer watching live — the usual requester — its id,
     * so it need not poll the list. If nobody is live the id is simply found in the list.
     */
    private fun onManualRecordResult(written: com.homecam.app.media.ClipRecorder.Written) {
        val peer = live?.peer ?: return
        graph.scope.launch {
            runCatching {
                peer.rpc.notify(
                    RpcMessage.Service.SESSION,
                    Controls.Session.MANUAL_RECORD_RESULT,
                    com.homecam.proto.ManualRecordResult.newBuilder()
                        .setClipId(written.id)
                        .setStartedAtMs(written.startedAtMs)
                        .setDurationMs(written.durationMs)
                        .build()
                        .toByteString(),
                )
            }.onFailure { Log.w(TAG, "manual-record result not delivered", it) }
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
        capture.setPowerSaving(false)
        audioDetection.setLive(true)
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

    override fun setOrientation(degrees: Int) {
        capture.setOrientation(degrees)
        // A camera propped on its side stays on its side across a restart.
        capturePrefs().edit().putInt(KEY_ORIENTATION, capture.orientationDegrees).apply()
    }

    private fun capturePrefs() = getSharedPreferences("homecam.capture", MODE_PRIVATE)

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

    override fun setSiren(on: Boolean, seconds: Int) {
        graph.diagnostics.siren(on, seconds)
        if (on) {
            siren.start(seconds.takeIf { it > 0 } ?: 30)
            recordResponseAction("siren", alert = true)
        } else {
            siren.stop()
        }
    }

    /** The spotlight is the torch used deliberately as a light; manual use is audited but not alerted. */
    override fun setSpotlight(on: Boolean) {
        capture.setTorch(on)
        autoSpotlightOn = false
        recordResponseAction(if (on) "spotlight on" else "spotlight off")
    }

    private fun maybeAutoSpotlight() {
        val settings = graph.settings.current
        val dark = detection.averageLuma < com.homecam.core.response.ResponsePolicy.DARK_LUMA_THRESHOLD
        if (!com.homecam.core.response.ResponsePolicy.autoSpotlightEngages(settings.autoSpotlight, hasFlash(), dark)) return
        if (!autoSpotlightOn) {
            capture.setTorch(true)
            autoSpotlightOn = true
            recordResponseAction("spotlight (auto)")
        }
        spotlightOffAtMs = System.currentTimeMillis() + AUTO_SPOTLIGHT_MS
    }

    /** Turns the automatic spotlight off once its window lapses, and fires due reminders. */
    private fun maintainResponses() {
        val now = System.currentTimeMillis()
        if (autoSpotlightOn && now > spotlightOffAtMs) {
            capture.setTorch(false)
            autoSpotlightOn = false
        }
        val settings = graph.settings.current
        val reminder = settings.detectionReminder
        if (com.homecam.core.response.ResponsePolicy.reminderDue(
                reminder.enabled, settings.armed, reminder.intervalMinutes, lastReminderMs, now,
            )
        ) {
            lastReminderMs = now
            showDetectionReminder()
        }
    }

    private fun showDetectionReminder() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            REMINDER_NOTIFICATION_ID,
            notification(graph.settings.current.alias, "Armed and detecting — this camera is watching."),
        )
    }

    private fun recordResponseAction(detail: String, alert: Boolean = false) {
        graph.scope.launch { graph.audit.record(AuditEvent.Kind.RESPONSE_ACTION, detail = detail) }
        if (alert) alerts.onResponseAction(detail)
    }

    private fun hasFlash(): Boolean =
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)

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
        audioDetection.setLive(false)
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
        }
        // Whenever the camera rather than the viewer ended it, the viewer is told why.
        if (reason != Bye.Reason.HANGUP) ending.peer.sendBye(reason, detail)
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
        private const val REMINDER_NOTIFICATION_ID = 2
        private const val PAUSED_NOTIFICATION_ID = 4
        private const val KEY_ORIENTATION = "orientation_degrees"
        private const val PAUSED_CHANNEL_ID = "homecam.camera.paused"
        private const val AUTO_SPOTLIGHT_MS = 20_000L
        private const val SHUTDOWN_GRACE_MS = 1_000
        private const val RESUME_COOLDOWN_MS = 3_000L
        private const val ACTION_RESUME_CAPTURE = "com.homecam.app.camera.RESUME_CAPTURE"
        // ROLLOVER_TICK_MS is 2s, so 150 ticks ≈ a heartbeat every 5 minutes.
        private const val HEARTBEAT_EVERY_TICKS = 150

        /**
         * Set by whoever asks for the start, so the diagnostics log says how it came up. A
         * new process that nobody in it asked for is Android restarting the sticky service
         * after killing it — the event GATE-C point 4 looks for.
         */
        @Volatile var startReason: String = "system"

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        private val blind = MutableStateFlow(false)
        val isBlind: StateFlow<Boolean> = blind.asStateFlow()

        /** Invoked once the service has entered the foreground, so the calling window can close. */
        @Volatile private var whenResumed: (() -> Unit)? = null

        private val liveViewer = MutableStateFlow<String?>(null)

        /** §2.7: the camera device says on its own screen when somebody is watching. */
        val liveViewerLabel: StateFlow<String?> = liveViewer.asStateFlow()

        fun start(context: Context) = launch(context, action = null)

        /**
         * Asks a running service to re-enter the foreground and restart capture, or
         * starts it when it is not running. [done] runs after that has happened, so a
         * caller that is itself the visible window can stay up until then.
         */
        fun resume(context: Context, done: () -> Unit = {}) {
            // Always through startForegroundService, even when the service is already up:
            // that call, made while the caller is visible, is what Android re-evaluates
            // camera access on — a repeat startForeground from inside the service is not.
            whenResumed = done
            runCatching { launch(context, ACTION_RESUME_CAPTURE) }
                .onFailure { error ->
                    Log.w(TAG, "could not start the camera service", error)
                    whenResumed = null
                    done()
                    BootReceiver.warnStopped(context, "Camera is not running")
                }
        }

        private fun launch(context: Context, action: String?) {
            val intent = Intent(context, CameraService::class.java)
            if (action != null) intent.action = action
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) = context.stopService(Intent(context, CameraService::class.java))
    }
}
