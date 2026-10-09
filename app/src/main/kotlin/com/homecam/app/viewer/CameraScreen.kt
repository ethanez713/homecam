package com.homecam.app.viewer

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.activity.compose.BackHandler
import com.homecam.app.AppGraph
import com.homecam.app.alerts.AlertKeys
import com.homecam.app.alerts.AlertPreferences
import com.homecam.app.alerts.AlertSubscriber
import com.homecam.app.camera.ui.AuditScreen
import com.homecam.app.data.CameraEntity
import com.homecam.app.viewer.ui.CameraSettingsScreen
import com.homecam.app.viewer.ui.CameraShell
import com.homecam.app.ui.kit.isLandscape
import com.homecam.app.viewer.ui.CameraTab
import com.homecam.app.viewer.ui.ClipsScreen
import com.homecam.app.viewer.ui.LiveControls
import com.homecam.app.viewer.ui.LiveScreen
import com.homecam.app.viewer.ui.LiveState
import com.homecam.app.viewer.ui.RemoteAddressSheet
import com.homecam.app.viewer.ui.RemoteRosterScreen
import com.homecam.app.data.toRetentionLimits
import com.homecam.core.audit.AuditChain
import com.homecam.core.health.HealthProtocol
import com.homecam.core.health.HealthReadout
import com.homecam.app.ui.RecordingPanelState
import com.homecam.app.ui.formatStamp
import com.homecam.core.rpc.ControlGating
import com.homecam.core.retention.ClipRef
import com.homecam.core.retention.RetentionPlanner
import com.homecam.core.sound.SoundClasses
import com.homecam.proto.Capabilities
import com.homecam.proto.SettingsSnapshot
import com.homecam.proto.Viewer
import kotlinx.coroutines.launch

private const val TAG = "CameraScreen"

/**
 * Everything the viewer does with one camera, over **one** connection. The tabs are views
 * onto the same authenticated session rather than four screens that each dial the camera
 * — waking a sleeping phone four times to read four things would be its own battery bug.
 */
@Composable
fun CameraScreen(node: ViewerNode, camera: CameraEntity, onExit: () -> Unit, openAtMs: Long? = null) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val scope = rememberCoroutineScope()

    var attempt by remember(camera.fingerprint) { mutableIntStateOf(0) }
    // A live session left running behind another app holds a voice-communication audio stream,
    // and while one is active Android sends the volume keys to the call stream instead of media.
    val foreground = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(Lifecycle.State.STARTED)
    var tab by remember(camera.fingerprint, openAtMs) { mutableStateOf(if (openAtMs != null) CameraTab.CLIPS else CameraTab.LIVE) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(TOAST_MS); toast = null } }
    val say: (String) -> Unit = { toast = it }
    var showingDevices by remember(camera.fingerprint) { mutableStateOf(false) }
    var showingLog by remember(camera.fingerprint) { mutableStateOf(false) }
    var state by remember(camera.fingerprint, attempt, foreground) {
        mutableStateOf<LiveState>(LiveState.Locating(camera.alias))
    }
    var session by remember(camera.fingerprint, attempt, foreground) { mutableStateOf<ViewerSession?>(null) }
    var settings by remember(camera.fingerprint, attempt, foreground) { mutableStateOf<SettingsSnapshot?>(null) }
    var capabilities by remember(camera.fingerprint, attempt, foreground) { mutableStateOf<Capabilities?>(null) }
    var projectedDays by remember(camera.fingerprint, attempt, foreground) { mutableStateOf<Double?>(null) }
    var viewers by remember(camera.fingerprint, attempt, foreground) { mutableStateOf<List<Viewer>?>(null) }
    var torch by remember(camera.fingerprint, attempt, foreground) { mutableStateOf(false) }
    var mic by remember(camera.fingerprint, attempt, foreground) { mutableStateOf(false) }
    var siren by remember(camera.fingerprint, attempt, foreground) { mutableStateOf(false) }
    var zoom by remember(camera.fingerprint, attempt, foreground) { mutableFloatStateOf(0f) }
    val alertKeys = remember { AlertKeys(context) }
    var alertsOn by remember { mutableStateOf(AlertPreferences.enabled(context)) }
    var healthAlertsOn by remember { mutableStateOf(AlertPreferences.health(context)) }
    // Without it Android 13+ drops every alert notification without telling anyone.
    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var remoteAddress by remember(camera.fingerprint) {
        mutableStateOf(
            if (camera.remoteHost.isBlank()) ""
            else if (camera.remotePort > 0) "${camera.remoteHost}:${camera.remotePort}"
            else camera.remoteHost
        )
    }

    var editingRemote by remember { mutableStateOf(false) }
    val saveRemoteAddress: (String) -> Unit = { entered ->
        val host = entered.substringBefore(':').trim()
        val port = entered.substringAfter(':', "").toIntOrNull() ?: 0
        remoteAddress = entered
        scope.launch { node.setRemoteAddress(camera.fingerprint, host, port) }
    }
    if (editingRemote) {
        RemoteAddressSheet(remoteAddress, { saveRemoteAddress(it); attempt++ }) { editingRemote = false }
    }

    // Captured, not read from state inside onDispose: when session changes from null to the
    // new connection, the old effect's disposal would otherwise close the new one.
    val sessionToClose = session
    DisposableEffect(sessionToClose) { onDispose { sessionToClose?.close() } }

    LaunchedEffect(camera.fingerprint, attempt, foreground) {
        if (!foreground) return@LaunchedEffect
        runCatching {
            val address = node.locate(camera) ?: error(notAnswering(camera))
            state = LiveState.Connecting(address.host, address.port)
            val client = node.connect(camera)
            val opened = ViewerSession(context, client)
            session = opened
            state = LiveState.Connected(
                client.verificationPhrase.orEmpty(),
                null,
                runCatching { client.link.runtimeStatus() }.getOrNull(),
                null,
                via = if (address.host == camera.remoteHost) "Remote" else "Home network",
            )
            settings = runCatching { client.link.settings() }.getOrNull()
            capabilities = client.link.capabilities()
            opened.start()
        }.onFailure {
            Log.w(TAG, "live session to ${camera.alias} failed at ${state::class.simpleName}", it)
            state = LiveState.Failed(describeFailure(it))
        }
    }

    val open = session
    if (open != null) {
        val video by open.video.collectAsState()
        val presence by open.presence.collectAsState()
        val ended by open.ended.collectAsState()
        LaunchedEffect(video, presence, ended) {
            val connected = state as? LiveState.Connected ?: return@LaunchedEffect
            ended?.let { Log.w(TAG, "live session to ${camera.alias} ended: $it") }
            state = ended?.let { LiveState.Failed(describeBye(it.first, it.second)) }
                ?: connected.copy(presence = presence, video = video)
        }
    }

    // The picture of the camera's health is only as good as its last reading, and a pause or a
    // timed revert can happen while this screen is open.
    LaunchedEffect(open) {
        val link = open?.link ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(STATUS_REFRESH_MS)
            val fresh = runCatching { link.runtimeStatus() }.getOrNull() ?: continue
            (state as? LiveState.Connected)?.let { state = it.copy(status = fresh) }
            runCatching { link.settings() }.getOrNull()?.let { settings = it }
        }
    }

    LaunchedEffect(settings?.armed, settings?.recordingMode) {
        settings?.let { node.rememberState(camera.fingerprint, it.armed, it.recordingMode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
    }

    // Recomputed when the mode changes, since that is what alters the recording rate and so
    // how far back the camera can remember (§5); a stale estimate is worse than none.
    LaunchedEffect(open, settings?.recordingMode, settings?.retention) {
        val current = settings
        projectedDays = if (open == null || current == null) null else runCatching {
            val refs = open.link.clips(limit = 1000).clipsList.map {
                ClipRef(it.id, it.startedAtMs, it.sizeBytes, it.pinned)
            }
            RetentionPlanner.projectedRetentionDays(refs, current.retention.toRetentionLimits(), System.currentTimeMillis())
        }.getOrNull()
    }

    // The paired-devices list is a screen of its own, reached from Settings.
    if (showingDevices) {
        BackHandler { showingDevices = false }
        LaunchedEffect(open) {
            viewers = open?.let { runCatching { it.link.viewers().viewersList }.getOrNull() }
        }
        RemoteRosterScreen(
            viewers = viewers,
            selfFingerprint = graph.identity.identity.shortFingerprint,
            onRevoke = { fingerprint ->
                edit(scope, open, say) {
                    it.link.revoke(fingerprint)
                    viewers = runCatching { it.link.viewers().viewersList }.getOrNull()
                }
            },
            onBack = { showingDevices = false },
        )
        return
    }

    if (showingLog) {
        BackHandler { showingLog = false }
        var remote by remember(open) { mutableStateOf<com.homecam.proto.AuditQuery?>(null) }
        LaunchedEffect(open) {
            remote = open?.let { runCatching { it.link.audit() }.getOrNull() }
        }
        AuditScreen(
            events = remote?.eventsList.orEmpty(),
            verification = remote?.let { AuditChain.Verification(it.chainValid, it.firstBrokenSeq) },
            onBack = { showingLog = false },
        )
        return
    }

    BackHandler { if (tab == CameraTab.LIVE) onExit() else tab = CameraTab.LIVE }

    CameraShell(
        alias = settings?.alias ?: camera.alias,
        status = when (val now = state) {
            is LiveState.Locating -> "Finding the camera"
            is LiveState.Connecting -> "Connecting"
            is LiveState.Connected -> "Connected · ${now.via.lowercase()}"
            is LiveState.Failed -> "Not connected"
        },
        armed = settings?.armed,
        tab = tab,
        onTab = { tab = it },
        onBack = onExit,
        tabsEnabled = open != null,
        toast = toast,
        // In landscape the picture has the screen, live or recorded.
        chrome = !(isLandscape() && (tab == CameraTab.CLIPS || (tab == CameraTab.LIVE && (state as? LiveState.Connected)?.video != null))),
    ) {
        when (tab) {
            CameraTab.LIVE -> LiveScreen(
                state = state,
                controls = open?.let {
                    // A camera that has not answered its capabilities (too old to) leaves
                    // every control available, exactly as before this negotiation existed.
                    val gates = capabilities?.let { caps -> ControlGating.gatesFor(caps) }
                    LiveControls(
                        torchOn = torch,
                        micOn = mic,
                        zoom = zoom,
                        onTorch = { on -> torch = on; scope.launch { it.setTorch(on) } },
                        onMic = { on -> mic = on; scope.launch { it.setPushToTalk(on) } },
                        onZoom = { value -> zoom = value; scope.launch { it.setZoom(value) } },
                        onRotate = { edit(scope, it, say) { session -> session.rotateQuarterTurn() } },
                        onSnapshot = {
                            edit(scope, it, say) { session ->
                                val still = session.link.snapshot()
                                val saved = com.homecam.app.viewer.ClipExport.imageToGallery(
                                    context, still.jpeg.toByteArray(), "homecam-${System.currentTimeMillis()}.jpg",
                                )
                                say(if (saved != null) "Snapshot saved to the gallery." else "The snapshot could not be saved.")
                            }
                        },
                        torchAvailable = gates?.torch ?: true,
                        pushToTalkAvailable = gates?.pushToTalk ?: true,
                        sirenAvailable = gates?.siren ?: false,
                        sirenOn = siren,
                        onSiren = { on -> siren = on; scope.launch { it.link.setSiren(on) } },
                        recording = (state as? LiveState.Connected)?.status?.let { status ->
                            RecordingPanelState(
                                mode = status.requestedRecordingMode,
                                readout = HealthReadout.recording(status, ::formatStamp),
                                projectedDays = projectedDays,
                                onChange = { mode, minutes ->
                                    edit(scope, open, say) {
                                        settings = it.link.setRecordingMode(mode, minutes)
                                        runCatching { it.link.runtimeStatus() }.getOrNull()?.let { fresh ->
                                            (state as? LiveState.Connected)?.let { c -> state = c.copy(status = fresh) }
                                        }
                                    }
                                },
                            )
                        },
                        health = (state as? LiveState.Connected)?.status?.let {
                            HealthReadout.card(it, capabilities, settings?.let { s -> HealthProtocol.configOf(s).batteryPausePercent } ?: 30)
                        }.orEmpty(),
                    )
                },
                onRetry = { attempt++ },
                onSetRemoteAddress = { editingRemote = true },
                onBack = onExit,
            )

            CameraTab.CLIPS -> {
                var clips by remember(open) { mutableStateOf<List<com.homecam.proto.Clip>?>(null) }
                var eventSpans by remember(open) { mutableStateOf<List<com.homecam.core.media.EventSpan>>(emptyList()) }
                var playing by remember(open) { mutableStateOf<java.io.File?>(null) }
                var playingId by remember(open) { mutableStateOf<String?>(null) }
                var downloading by remember(open) { mutableStateOf<String?>(null) }
                var downloadProgress by remember(open) { mutableStateOf<com.homecam.core.media.DownloadProgress?>(null) }
                suspend fun refresh() {
                    clips = open?.let { runCatching { it.link.clips().clipsList }.getOrNull() }
                    eventSpans = open?.let { s ->
                        clips?.minOfOrNull { it.startedAtMs }?.let { since -> runCatching { s.link.eventSpans(since) }.getOrNull() }
                    }.orEmpty()
                }
                LaunchedEffect(open) { refresh() }

                val nearCeiling = clips?.let { list ->
                    settings?.let { s ->
                        RetentionPlanner.pinnedNearCeiling(
                            list.map { ClipRef(it.id, it.startedAtMs, it.sizeBytes, it.pinned) },
                            s.retention.toRetentionLimits(),
                        )
                    }
                } ?: false

                suspend fun fetch(session: ViewerSession, clip: com.homecam.proto.Clip): java.io.File {
                    val file = java.io.File(context.cacheDir, "clips/${clip.id}.mp4")
                    if (!file.exists()) {
                        try {
                            session.link.downloadClip(clip.id, file) { received ->
                                downloadProgress = com.homecam.core.media.DownloadProgress(received, clip.sizeBytes)
                            }
                        } finally {
                            downloadProgress = null
                        }
                    }
                    return file
                }

                ClipsScreen(
                    clips = clips,
                    eventSpans = eventSpans,
                    playing = playing,
                    playingId = playingId,
                    downloadingId = downloading,
                    downloadProgress = downloadProgress,
                    projectedDays = projectedDays,
                    showBoxes = settings?.boundingBoxOverlay ?: true,
                    loadThumbnail = open?.let { s -> { id -> s.link.thumbnail(id) } },
                    pinnedNearCeiling = nearCeiling,
                    onPlay = { clip ->
                        edit(scope, open, say) {
                            downloading = clip.id
                            runCatching { fetch(it, clip) }.onSuccess { file -> playing = file; playingId = clip.id }
                            downloading = null
                        }
                    },
                    onExport = { chosen ->
                        edit(scope, open, say) {
                            chosen.forEach { clip ->
                                downloading = clip.id
                                runCatching { fetch(it, clip) }.onSuccess { file ->
                                    com.homecam.app.viewer.ClipExport.toGallery(context, file, "homecam-${clip.id}.mp4")
                                }
                            }
                            downloading = null
                            say(if (chosen.size == 1) "Saved to the gallery." else "Saved ${chosen.size} clips to the gallery.")
                        }
                    },
                    onPin = { clip ->
                        edit(scope, open, say) { it.link.pinClip(clip.id, !clip.pinned); refresh() }
                    },
                    onDelete = { doomed ->
                        edit(scope, open, say) {
                            doomed.forEach { clip -> it.link.deleteClip(clip.id) }
                            if (doomed.any { clip -> clip.id == playingId }) { playing = null; playingId = null }
                            refresh()
                            say(if (doomed.size == 1) "Clip deleted." else "${doomed.size} clips deleted.")
                        }
                    },
                    onBack = onExit,
                    openAtMs = openAtMs,
                )
            }

            CameraTab.SETTINGS -> CameraSettingsScreen(
                settings = settings,
                onArmed = { armed -> edit(scope, open, say) { settings = it.link.setArmed(armed) } },
                onAlias = { alias -> edit(scope, open, say) { settings = it.link.setAlias(alias) } },
                onMicrophone = { on ->
                    edit(scope, open, say) { settings = it.link.setMicrophone(on) }
                },
                onRecordingMode = { mode ->
                    edit(scope, open, say) { settings = it.link.setRecordingMode(mode) }
                },
                projectedDays = projectedDays,
                protection = settings?.takeIf { capabilities?.hardwareProtection == true }
                    ?.let { HealthProtocol.configOf(it) },
                onProtection = { protection ->
                    edit(scope, open, say) { settings = it.link.setHardwareProtection(protection) }
                },
                autoSpotlightSupported = capabilities?.spotlight ?: true,
                autoSpotlight = settings?.autoSpotlight ?: false,
                onAutoSpotlight = { on ->
                    edit(scope, open, say) { settings = it.link.setAutoSpotlight(on) }
                },
                reminderEnabled = settings?.detectionReminder?.enabled ?: false,
                onDetectionReminder = { on ->
                    edit(scope, open, say) { settings = it.link.setDetectionReminder(on, 60) }
                },
                soundDetectionEnabled = settings?.soundDetection?.enabled ?: false,
                onSoundDetection = { on ->
                    edit(scope, open, say) { settings = it.link.setSoundDetection(on) }
                },
                soundClasses = SoundClasses.pushSelection(settings?.takeIf { it.hasSoundRecognition() }?.soundRecognition?.classesList),
                onSoundClass = { soundClass, on ->
                    edit(scope, open, say) {
                        val next = SoundClasses.pushSelection(settings?.takeIf { it.hasSoundRecognition() }?.soundRecognition?.classesList).toMutableSet()
                        if (on) next.add(soundClass) else next.remove(soundClass)
                        settings = it.link.setSoundRecognition(next.toList())
                    }
                },
                onDetectionMode = { person, vehicle, pet, packages ->
                    edit(scope, open, say) {
                        settings = it.link.setDetectionMode(person, vehicle, pet, packages)
                    }
                },
                alertsEnabled = alertsOn,
                onAlerts = { on ->
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    edit(scope, open, say) {
                        it.link.registerAlerts(
                            alertPublicKey = alertKeys.publicKeySpki,
                            topic = alertKeys.topic(context),
                            endpoint = AlertSubscriber.endpointOf(context),
                            enabled = on,
                            healthAlerts = healthAlertsOn,
                        )
                        AlertPreferences.setEnabled(context, on)
                        alertsOn = on
                        if (on) AlertSubscriber.start(context) else AlertSubscriber.stop(context)
                    }
                },
                healthAlertsEnabled = healthAlertsOn,
                onHealthAlerts = { on ->
                    edit(scope, open, say) {
                        it.link.registerAlerts(
                            alertPublicKey = alertKeys.publicKeySpki,
                            topic = alertKeys.topic(context),
                            endpoint = AlertSubscriber.endpointOf(context),
                            enabled = alertsOn,
                            healthAlerts = on,
                        )
                        AlertPreferences.setHealth(context, on)
                        healthAlertsOn = on
                    }
                },
                remoteAddress = remoteAddress,
                onRemoteAddress = saveRemoteAddress,
                onDevices = { showingDevices = true },
                onLog = { showingLog = true },
                onForget = { scope.launch { node.forget(camera.fingerprint); onExit() } },
                verificationPhrase = (state as? LiveState.Connected)?.phrase.orEmpty(),
                fingerprint = camera.fingerprint,
            )
        }
    }
}

private fun edit(
    scope: kotlinx.coroutines.CoroutineScope,
    session: ViewerSession?,
    say: (String) -> Unit,
    block: suspend (ViewerSession) -> Unit,
) {
    val open = session ?: return say("Not connected to the camera. Nothing was changed.")
    scope.launch {
        runCatching { block(open) }.onFailure {
            Log.w(TAG, "camera request failed", it)
            say("The camera did not answer. Nothing was changed.")
        }
    }
}

private const val TOAST_MS = 2_800L
private const val STATUS_REFRESH_MS = 20_000L

/**
 * Off the home network the remote address is the only way to the camera, and the Settings
 * tab that holds it only exists once connected — so the message says where to set it and
 * when that is possible.
 */
private fun notAnswering(camera: CameraEntity): String =
    if (camera.remoteHost.isBlank()) {
        "${camera.alias} is not answering on this network, and it has no remote address. " +
            "To watch from anywhere else, connect once at home and set its Tailscale name " +
            "under Settings → Remote access."
    } else {
        "${camera.alias} is not answering on this network or at ${camera.remoteHost}."
    }
