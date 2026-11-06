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
import com.homecam.app.detect.SharedObjectDetector
import com.homecam.app.media.CameraBinder
import com.homecam.app.media.CameraMediaSession
import com.homecam.app.media.CameraXVideoCapturer
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.WebRtc
import com.homecam.core.diagnostics.PowerSavingReason
import com.homecam.core.health.Axis
import com.homecam.core.lens.LensEntry
import com.homecam.core.lens.LensRegions
import com.homecam.core.lens.LensSelections
import com.homecam.core.media.PowerSaving
import com.homecam.core.health.HealthProtocol
import com.homecam.core.health.HealthSample
import com.homecam.core.health.HealthState
import com.homecam.core.health.RecordingTimer
import com.homecam.core.media.CaptureWatchdog
import com.homecam.core.rpc.Controls
import com.homecam.proto.AuditEvent
import com.homecam.proto.Bye
import com.homecam.proto.Candidate
import com.homecam.proto.Sdp
import com.homecam.proto.SessionConfig
import com.homecam.proto.PrivacyMask
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
import kotlinx.coroutines.sync.withLock
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
    private lateinit var binder: CameraBinder
    private lateinit var clips: ClipStore
    private lateinit var detector: SharedObjectDetector
    private lateinit var primary: LensPipeline
    private val capture: ContinuousCapture get() = primary.capture
    private val recorder: Recorder get() = primary.recorder
    private val detection: DetectionPipeline get() = primary.detection
    @Volatile private var secondary: LensPipeline? = null
    private lateinit var powerSaving: PowerSaving
    private lateinit var hardware: HardwareMonitor
    private lateinit var audioDetection: AudioDetectionPipeline
    private val screen = ScreenDimmer().also { ScreenDimmer.attach(it) }
    private lateinit var throttle: ThermalThrottle
    private var captureThrottled = false
    private var detectorThrottled = false
    private lateinit var alerts: Alerts
    private val siren by lazy { Siren(this) }
    @Volatile private var spotlightOffAtMs = 0L
    @Volatile private var autoSpotlightOn = false
    @Volatile private var autoTorch: ContinuousCapture? = null
    private var secondaryAnnounced: Boolean? = null
    /** The HAL refused this pair. Health must not start it again until the selection changes. */
    private var secondaryBindFailed = false
    @Volatile private var secondaryPaused = false
    private var spotlightLoggedOn = false
    private var spotlightLoggedAuto = false
    private var spotlightSinceMs = 0L
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
        val sirenInterrupted = Siren.recoverInterrupted(this)
        graph = AppGraph.get(this)
        node = graph.cameraNode
        graph.diagnostics.start(startReason, sirenInterrupted = sirenInterrupted)
        startReason = "restart"
        createChannel()
        startForegroundWithNotification(idleNotification())

        binder = CameraBinder(this, this).also { it.onConcurrentBindFailed = ::onConcurrentBindFailed }
        clips = ClipStore(this, graph.database.clips())
        detector = SharedObjectDetector(this)
        primary = openPipeline(
            lensId = LensSelections.primaryOf(graph.settings.current),
            lens = selectedLens(),
            reportManual = true,
        )
        powerSaving = PowerSaving(
            setSaving = { saving ->
                captures().fold(false) { changed, each -> each.setPowerSaving(saving) || changed }
            },
            log = { engaged, reason -> graph.diagnostics.powerSaving(engaged, reason) },
        )
        throttle = ThermalThrottle(
            setCaptureThrottled = { on ->
                captureThrottled = on
                captures().forEach { it.setThermalThrottle(on) }
            },
            setDetectorThrottled = { on ->
                detectorThrottled = on
                pipelines().forEach { it.detection.setThrottled(on) }
            },
            live = LiveSessionThrottle(
                session = { live?.media },
                format = { capture.format },
                applyFormat = { capture.setFormat(it) },
            ),
            screen = screen,
        ).also { created ->
            created.listener = ThrottleStepListener { step, engaged, _, reading ->
                graph.diagnostics.throttle(step, engaged, reading.batteryTempC, reading.thermalStatus)
            }
        }
        hardware = HardwareMonitor(graph, clips, ::onHealthChange).also { it.start() }
        startClipRollover()

        server = SignalingServer(graph, node).also {
            it.listener = this
            it.mediaController = this
            it.clipStore = clips
            it.statusProvider.hardware = hardware
            it.statusProvider.secondaryPaused = secondaryPaused
            it.start()
        }
        alerts = Alerts(graph)
        audioDetection = AudioDetectionPipeline(
            context = this,
            settings = { graph.settings.current },
            onEvent = { category, levelDb, push ->
                val now = System.currentTimeMillis()
                recorders().forEach { it.onSoundEvent(category, now) }
                if (push) alerts.onDetection(listOf(category), clipId = "", thumbnailJpeg = null)
                graph.diagnostics.soundEvent(category, levelDb)
            },
        ).also {
            it.onSound = { powerSaving.release(PowerSavingReason.SOUND) }
            it.onListen = { feed, started, reason -> graph.diagnostics.audioListen(feed, started, reason) }
            it.sync()
        }
        WebRtc.capturedAudio = { audioDetection.onLiveSamples(it) }
        ClipPruneWorker.schedule(this)
        observeArming()
        observeLens()
        observeRecordingMode()
        observeHardwareProtection()
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
        // First, so a later teardown failure cannot skip putting the alarm volume back.
        siren.stop()
        if (::throttle.isInitialized) throttle.forceRelease()
        ScreenDimmer.detach(screen)
        cancelCameraPausedNotification()
        graph.diagnostics.stop("destroy")
        // Before the server stops, or the viewer only ever sees the socket drop.
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_GRACE_MS.toLong()) { endLive(Bye.Reason.CAMERA_DISABLED, "camera stopped") }
        }
        mdns?.stop()
        hardware.stop()
        WebRtc.capturedAudio = null
        audioDetection.close()
        secondary?.let { stopSecondary() }
        if (::primary.isInitialized) primary.stop()
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
                recorders().forEach { it.tick() }
                powerSaving.tick(sceneIdle = live == null && recorders().none { it.eventInProgress })
                statusFromPipeline()
                if (ticks % HARDWARE_SAMPLE_EVERY_TICKS == 0) sampleHardware()
                revertTimedRecording()
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
                recording = recorders().any { it.recording },
                mode = graph.settings.current.recordingMode.name,
                batteryPausePct = HealthProtocol.configOf(graph.settings.current).batteryPausePercent,
                chargeCounterUah = hardware.chargeCounterUah,
            )
        }
    }

    private fun onHealthChange(previous: HealthState, current: HealthState, reading: HealthSample) {
        val batteryPausePct = HealthProtocol.configOf(graph.settings.current).batteryPausePercent
        Axis.entries.filter { previous.level(it) != current.level(it) }.forEach {
            graph.diagnostics.healthState(it, previous.level(it), current.level(it), reading, current, batteryPausePct)
        }
        if (previous.cap != current.cap) recorders().forEach { it.setCap(current.cap) }
        if (::alerts.isInitialized) alerts.onHealth(current, previous)
        if (previous.cap != current.cap || previous.capReason != current.capReason) {
            val requested = graph.settings.current.recordingMode
            graph.diagnostics.recordingCap(
                current, requested, HealthProtocol.effectiveMode(requested, current.cap), reading.batteryTempC,
            )
        }
        // The window and the capture request are main-thread objects. A thermal callback
        // arrives on the IO scope that sampled it.
        val secondaryAllowanceChanged = previous.secondaryAllowed != current.secondaryAllowed
        lifecycleScope.launch {
            throttle.onHealth(current, reading)
            if (secondaryAllowanceChanged) reconcileSecondary("health")
        }
    }

    private suspend fun revertTimedRecording() {
        val current = graph.settings.current
        val target = RecordingTimer.dueMode(current.recordingRevert, System.currentTimeMillis()) ?: return
        graph.diagnostics.recordingReverted(current.recordingMode, target, current.recordingRevert.atMs)
        graph.settings.update {
            setRecordingMode(target)
            clearRecordingRevert()
        }
    }

    private suspend fun sampleHardware() {
        runCatching { hardware.sample() }.onFailure {
            Log.w(TAG, "hardware sample failed", it)
            graph.diagnostics.error("hardware_sample", it.message)
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
        if (!::primary.isInitialized || !watchdog.isBlind) {
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
                val settings = graph.settings.current
                pipelines().forEach { pipeline ->
                    pipeline.capture.stop()
                    pipeline.capture.start(
                        mask = LensRegions.maskFor(settings, pipeline.lensId),
                        lens = pipeline.lens,
                    )
                }
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
            graph.settings.settings.map { it.armed }.distinctUntilChanged().collect { armed ->
                recorders().forEach { it.setArmed(armed) }
            }
        }
    }

    private fun selectedLens(): LensEntry? = LensSource.lens(this, LensSelections.primaryOf(graph.settings.current))

    private fun observeLens() {
        lifecycleScope.launch {
            graph.settings.settings
                .map { LensSelections.primaryOf(it) to it.lens.secondaryList.toList() }
                .distinctUntilChanged()
                .collect { (primaryId, _) ->
                    applyPrimaryLens(primaryId)
                    reconcileSecondary("selection")
                }
        }
    }

    private fun applyPrimaryLens(id: String) {
        if (primary.lensId != id) graph.diagnostics.lensSwitch(primary.lensId, id, primary.capture.running)
        primary.lensId = id
        primary.recorder.lensId = id
        val entry = LensSource.lens(this, id) ?: return
        primary.lens = entry
        primary.capture.setLens(entry) { LensRegions.maskFor(graph.settings.current, id) }
    }

    /** A stored secondary that the proven pairs no longer cover is not selected. */
    private fun validatedSecondary(): LensEntry? {
        val selection = graph.settings.current.lens
        if (selection.secondaryCount == 0) return null
        val advertised = LensSource.lenses(this).map { it.id }
        if (LensSelections.rejection(selection, advertised, ConcurrentPairs.read(this)) != null) return null
        return LensSource.lens(this, selection.getSecondary(0))
    }

    private fun reconcileSecondary(reason: String) {
        if (reason == "selection") secondaryBindFailed = false
        val wanted = validatedSecondary()
        if (secondaryBindFailed) {
            publishPaused(wanted != null && secondary == null)
            return
        }
        val running = secondary
        if (wanted != null && hardware.state.secondaryAllowed) {
            if (running != null && running.lensId == wanted.id) {
                val capped = cappedFormat(wanted.id, capture.format)
                if (running.capture.format != capped) running.capture.setFormat(capped)
                publishPaused(false)
                return
            }
            if (running != null) {
                stopSecondary()
                announceSecondary(on = false, reason = "selection")
            }
            startSecondary(wanted)
            announceSecondary(on = true, reason = reason)
        } else if (running != null) {
            stopSecondary()
            announceSecondary(on = false, reason = if (wanted == null) "selection" else "health")
        } else if (wanted != null) {
            announceSecondary(on = false, reason = "health")
        }
        publishPaused(wanted != null && secondary == null)
    }

    private fun onConcurrentBindFailed(error: Exception) {
        secondaryBindFailed = true
        stopSecondary()
        publishPaused(true)
        graph.diagnostics.secondaryLens("off", "bind_failed")
        secondaryAnnounced = false
        graph.diagnostics.error("concurrent_bind", error.message)
    }

    private fun startSecondary(entry: LensEntry) {
        // The proven pair names the largest size both lenses held. The second lens also
        // stays at or below whatever the primary is running right now.
        val format = cappedFormat(entry.id, capture.format)
        val pipeline = openPipeline(entry.id, entry, reportManual = false, format = format)
        secondary = pipeline
        live?.media?.onCaptureRunning(entry.id, pipeline.capture)
    }

    private fun stopSecondary() {
        val stopping = secondary ?: return
        live?.media?.onCaptureStopped(stopping.lensId)
        if (autoSpotlightOn && autoTorch === stopping.capture) {
            stopping.capture.setTorch(false)
            autoSpotlightOn = false
            noteSpotlight(on = false, auto = true)
            autoTorch = null
        }
        stopping.stop()
        secondary = null
    }

    private fun cappedFormat(secondaryId: String, primaryFormat: ContinuousCapture.Format): ContinuousCapture.Format {
        val pair = ConcurrentPairs.read(this).firstOrNull { candidate ->
            (candidate.a == primary.lensId && candidate.b == secondaryId) ||
                (candidate.b == primary.lensId && candidate.a == secondaryId)
        } ?: return primaryFormat
        val maxWidth = pair.max.width
        val maxHeight = pair.max.height
        if (maxWidth <= 0 || maxHeight <= 0) return primaryFormat
        return primaryFormat.copy(
            width = minOf(primaryFormat.width, maxWidth),
            height = minOf(primaryFormat.height, maxHeight),
        )
    }

    private fun applyCaptureFormat(next: ContinuousCapture.Format) {
        capture.setFormat(next)
        val second = secondary ?: return
        val capped = cappedFormat(second.lensId, capture.format)
        if (second.capture.format != capped) second.capture.setFormat(capped)
    }

    private fun announceSecondary(on: Boolean, reason: String) {
        if (secondaryAnnounced == on) return
        secondaryAnnounced = on
        graph.diagnostics.secondaryLens(if (on) "on" else "off", reason)
    }

    private fun publishPaused(paused: Boolean) {
        secondaryPaused = paused
        server?.statusProvider?.secondaryPaused = paused
    }

    private fun pipelines(): List<LensPipeline> =
        listOfNotNull(if (::primary.isInitialized) primary else null, secondary)

    private fun captures(): List<ContinuousCapture> = pipelines().map { it.capture }

    private fun recorders(): List<Recorder> = pipelines().map { it.recorder }

    private fun openPipeline(
        lensId: String,
        lens: LensEntry?,
        reportManual: Boolean,
        format: ContinuousCapture.Format? = null,
    ): LensPipeline {
        val pipeCapture = ContinuousCapture(this, this, binder, bindFirst = !::primary.isInitialized)
        val inherit = ::primary.isInitialized
        pipeCapture.setOrientation(
            if (inherit) capture.orientationDegrees else capturePrefs().getInt(KEY_ORIENTATION, 0),
        )
        if (inherit) {
            pipeCapture.setPowerSaving(powerSaving.isEngaged)
            pipeCapture.setThermalThrottle(captureThrottled)
        }
        pipeCapture.start(
            format = format ?: pipeCapture.format,
            mask = LensRegions.maskFor(graph.settings.current, lensId),
            lens = lens,
        )
        val pipeRecorder = Recorder(graph, pipeCapture, clips, lensId).also { created ->
            created.start()
            created.setArmed(graph.settings.current.armed)
            created.setMode(graph.settings.current.recordingMode)
            if (inherit) created.setCap(hardware.state.cap)
            if (reportManual) created.onManualResult = ::onManualRecordResult
        }
        var pipeline: LensPipeline? = null
        val pipeDetection = DetectionPipeline(
            context = this,
            settings = { graph.settings.current },
            lensId = { pipeline?.lensId ?: lensId },
            onMotion = { powerSaving.release(PowerSavingReason.MOTION) },
            detect = detector.attach(),
            onEvent = event@{ detections, thumbnailJpeg ->
                val current = pipeline ?: return@event
                onLensDetection(current, detections, thumbnailJpeg)
            },
        )
        if (inherit && detectorThrottled) pipeDetection.setThrottled(true)
        pipeCapture.addSink(pipeDetection)
        return LensPipeline(lensId, pipeCapture, pipeRecorder, pipeDetection, lens, detector::detach)
            .also { pipeline = it }
    }

    private fun onLensDetection(
        pipeline: LensPipeline,
        detections: List<DetectionPipeline.LabelledDetection>,
        thumbnailJpeg: ByteArray?,
    ) {
        if (detections.isNotEmpty()) powerSaving.release(PowerSavingReason.DETECTION)
        pipeline.recorder.onDetection(detections, System.currentTimeMillis())
        if (detections.isEmpty()) return
        val lens = if (secondary == null) null else LensSelections.labelOf(pipeline.lensId)
        alerts.onDetection(
            detections.map { it.category },
            clipId = "",
            thumbnailJpeg = thumbnailJpeg,
            lens = lens,
        )
        maybeAutoSpotlight(pipeline)
    }

    private fun observeHardwareProtection() {
        lifecycleScope.launch {
            graph.settings.settings.map { HealthProtocol.configOf(it) }.distinctUntilChanged().collect {
                hardware.configure(it)
            }
        }
    }

    private fun observeRecordingMode() {
        lifecycleScope.launch {
            graph.settings.settings.map { it.recordingMode }.distinctUntilChanged().collect { mode ->
                recorders().forEach { it.setMode(mode) }
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

        // Re-checked and audited in one step with revoke: setup below takes long enough for a
        // revoke to land in the middle, and a row written after it would claim a revoked viewer
        // opened a session.
        val admitted = node.admission.withLock {
            val key = peer.viewer
            if (key == null || !node.isAllowed(key)) return@withLock false
            graph.audit.record(
                AuditEvent.Kind.SESSION_OPENED,
                viewerFingerprint = viewerFingerprint,
                viewerLabel = peer.label,
                sourceIp = peer.sourceIp,
                detail = "live video",
            )
            true
        }
        if (!admitted) throw IllegalStateException("viewer $viewerFingerprint is not admitted")

        val previous = live
        if (previous?.peer === peer) endLive(Bye.Reason.HANGUP, "new lens set", previous)
        else endLive(Bye.Reason.SESSION_REPLACED, "replaced by $viewerFingerprint")

        val opened = arrayOfNulls<LiveSession>(1)
        val primaryId = LensSelections.primaryOf(graph.settings.current)
        val secondaryId = validatedSecondary()?.id
        val secondaryCapture = secondary?.takeIf { it.lensId == secondaryId }?.capture
        val media = CameraMediaSession(
            context = this,
            config = CameraMediaSession.Config(
                audio = config.audio && graph.settings.current.microphone,
            ),
            transport = object : CameraMediaSession.Transport {
                override fun sendOffer(sdp: Sdp) =
                    peer.send(SignalMessage.newBuilder().setOffer(sdp).build())

                override fun sendCandidate(candidate: Candidate) =
                    peer.send(SignalMessage.newBuilder().setCandidate(candidate).build())

                override fun tearDown(reason: Bye.Reason, detail: String) {
                    val mine = opened[0] ?: return
                    graph.scope.launch { endLive(reason, detail, mine) }
                }
            },
        )
        val session = LiveSession(peer, media)
        opened[0] = session
        live = session
        // A revoke during setup closed the socket before this session was visible to its teardown.
        if (!peer.socket.isOpen) {
            endLive(Bye.Reason.REVOKED, "viewer gone during setup")
            throw IllegalStateException("viewer $viewerFingerprint disconnected during setup")
        }
        media.start(
            buildList {
                add(CameraMediaSession.LensVideo(primaryId, capture))
                if (secondaryId != null) add(CameraMediaSession.LensVideo(secondaryId, secondaryCapture))
            },
            throttle.bitrateCapBps,
        )
        graph.diagnostics.liveStart(media.id, listOfNotNull(primaryId, secondaryId))
        throttle.onSessionOpen()
        powerSaving.release(PowerSavingReason.VIEWER)
        audioDetection.setLive(true)
        showLive(peer.label)
        alerts.onSessionStarted(peer.label, viewerFingerprint)
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

    override fun setLiveLenses(shown: List<String>) {
        live?.media?.setShown(shown)
    }

    override fun setPushToTalk(active: Boolean) = Unit

    override suspend fun setResolution(resolution: com.homecam.proto.Resolution): SessionStatus {
        val next = ContinuousCapture.Format(
            width = resolution.width.coerceIn(320, 1920),
            height = resolution.height.coerceIn(240, 1080),
            framerate = resolution.fps.coerceIn(5, 30),
        )
        // A throttled live session stays at the lower tier; the request is what release restores.
        if (!throttle.offerFormat(next)) applyCaptureFormat(next)
        return sessionStatus(live?.peer?.viewer?.shortFingerprint.orEmpty())
    }

    override fun setOrientation(degrees: Int) {
        captures().forEach { it.setOrientation(degrees) }
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
        if (start && recorder.manualRecordingRefused) {
            throw com.homecam.core.rpc.RpcException(
                com.homecam.proto.RpcError.Code.UNAVAILABLE,
                "Recording is paused: ${HealthProtocol.describeCause(hardware.state.capReason)}",
            )
        }
        if (start) {
            powerSaving.release(PowerSavingReason.MANUAL_CLIP)
            recorders().forEach { it.recordManually(seconds) }
        } else {
            recorders().forEach { it.stopManual() }
        }
    }

    override fun setSiren(on: Boolean, seconds: Int, by: Actor?) {
        graph.diagnostics.siren(on, seconds)
        if (on) {
            siren.start(seconds.takeIf { it > 0 } ?: 30)
            recordResponseAction("siren", alert = true, by = by)
        } else {
            siren.stop()
        }
    }

    /** The spotlight is the torch used deliberately as a light; manual use is audited but not alerted. */
    override fun setSpotlight(on: Boolean, by: Actor?) {
        val other = autoTorch?.takeIf { it !== capture }
        other?.setTorch(false)
        capture.setTorch(on)
        val wasAuto = autoSpotlightOn
        autoSpotlightOn = false
        if (on) autoTorch = capture
        recordResponseAction(if (on) "spotlight on" else "spotlight off", by = by)
        noteSpotlight(on = on, auto = if (on) false else wasAuto)
        if (!on) autoTorch = null
    }

    private fun maybeAutoSpotlight(from: LensPipeline) {
        val flash = spotlightPipeline() ?: return
        if (from !== flash) return
        val settings = graph.settings.current
        val dark = flash.detection.averageLuma < com.homecam.core.response.ResponsePolicy.DARK_LUMA_THRESHOLD
        if (!com.homecam.core.response.ResponsePolicy.autoSpotlightEngages(settings.autoSpotlight, true, dark)) return
        if (!autoSpotlightOn) {
            flash.capture.setTorch(true)
            autoTorch = flash.capture
            autoSpotlightOn = true
            recordResponseAction("spotlight (auto)")
            noteSpotlight(on = true, auto = true)
        }
        spotlightOffAtMs = System.currentTimeMillis() + AUTO_SPOTLIGHT_MS
    }

    /** The lens that can light the scene. A single lens with no catalog entry keeps the old device-flash check. */
    private fun spotlightPipeline(): LensPipeline? {
        pipelines().firstOrNull { it.lens?.hasFlash == true }?.let { return it }
        val only = pipelines().singleOrNull() ?: return null
        val deviceFlash = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)
        return only.takeIf { it.lens == null && deviceFlash }
    }

    /** Turns the automatic spotlight off once its window lapses, and fires due reminders. */
    private fun maintainResponses() {
        val now = System.currentTimeMillis()
        if (autoSpotlightOn && now > spotlightOffAtMs) {
            (autoTorch ?: capture).setTorch(false)
            autoSpotlightOn = false
            noteSpotlight(on = false, auto = true)
            autoTorch = null
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

    private fun recordResponseAction(detail: String, alert: Boolean = false, by: Actor? = null) {
        graph.scope.launch {
            graph.audit.record(
                AuditEvent.Kind.RESPONSE_ACTION,
                viewerFingerprint = by?.fingerprint.orEmpty(),
                viewerLabel = by?.label.orEmpty(),
                sourceIp = by?.sourceIp.orEmpty(),
                detail = detail,
            )
        }
        if (alert) alerts.onResponseAction(detail)
    }

    /** Logs only when the light, or whether it is the automatic one, changes. */
    private fun noteSpotlight(on: Boolean, auto: Boolean) {
        if (on == spotlightLoggedOn && auto == spotlightLoggedAuto) return
        val durationMs = if (!on && spotlightSinceMs != 0L) System.currentTimeMillis() - spotlightSinceMs else null
        if (on && !spotlightLoggedOn) spotlightSinceMs = System.currentTimeMillis()
        if (!on) spotlightSinceMs = 0L
        spotlightLoggedOn = on
        spotlightLoggedAuto = auto
        val luma = when {
            auto && autoTorch != null ->
                pipelines().firstOrNull { it.capture === autoTorch }?.detection?.averageLuma ?: 0f
            ::primary.isInitialized -> detection.averageLuma
            else -> 0f
        }
        graph.diagnostics.spotlight(on, auto, luma, durationMs)
    }

    /**
     * A mask edited while somebody is watching has to take effect on that stream, not the
     * next one — the whole point is that the pixels never leave. Only the lens that was
     * edited changes; the other keeps the mask it is already applying.
     */
    override fun setPrivacyMask(lensId: String, mask: PrivacyMask) {
        pipelines().filter { it.lensId == lensId }.forEach { it.capture.setPrivacyMask(mask) }
    }

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

    private suspend fun endLive(reason: Bye.Reason, detail: String, expected: LiveSession? = live) {
        val ending = expected ?: return
        if (live !== ending) return
        live = null
        graph.diagnostics.liveEnd(ending.media.id, reason.name, detail, System.currentTimeMillis() - ending.startedAtMs)
        ending.media.close()
        if (::throttle.isInitialized) throttle.onSessionClose()
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

        // 15 ticks of 2 s: a reading every 30 s is plenty for a trend measured in minutes.
        private const val HARDWARE_SAMPLE_EVERY_TICKS = 15
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
