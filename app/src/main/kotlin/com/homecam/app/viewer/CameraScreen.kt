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
import com.homecam.core.rpc.ControlGating
import com.homecam.core.retention.ClipRef
import com.homecam.core.retention.RetentionPlanner
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
fun CameraScreen(node: ViewerNode, camera: CameraEntity, onExit: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val scope = rememberCoroutineScope()

    var attempt by remember(camera.fingerprint) { mutableIntStateOf(0) }
    var tab by remember(camera.fingerprint) { mutableStateOf(CameraTab.LIVE) }
    var showingDevices by remember(camera.fingerprint) { mutableStateOf(false) }
    var showingLog by remember(camera.fingerprint) { mutableStateOf(false) }
    var playerOpen by remember(camera.fingerprint) { mutableStateOf(false) }
    var state by remember(camera.fingerprint, attempt) {
        mutableStateOf<LiveState>(LiveState.Locating(camera.alias))
    }
    var session by remember(camera.fingerprint, attempt) { mutableStateOf<ViewerSession?>(null) }
    var settings by remember(camera.fingerprint, attempt) { mutableStateOf<SettingsSnapshot?>(null) }
    var capabilities by remember(camera.fingerprint, attempt) { mutableStateOf<Capabilities?>(null) }
    var projectedDays by remember(camera.fingerprint, attempt) { mutableStateOf<Double?>(null) }
    var viewers by remember(camera.fingerprint, attempt) { mutableStateOf<List<Viewer>?>(null) }
    var torch by remember(camera.fingerprint, attempt) { mutableStateOf(false) }
    var mic by remember(camera.fingerprint, attempt) { mutableStateOf(false) }
    var siren by remember(camera.fingerprint, attempt) { mutableStateOf(false) }
    var zoom by remember(camera.fingerprint, attempt) { mutableFloatStateOf(0f) }
    val alertKeys = remember { AlertKeys(context) }
    var alertsOn by remember { mutableStateOf(AlertPreferences.enabled(context)) }
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

    LaunchedEffect(camera.fingerprint, attempt) {
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
                edit(scope, open) {
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

    BackHandler(enabled = !playerOpen) { if (tab == CameraTab.LIVE) onExit() else tab = CameraTab.LIVE }

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
        chrome = !playerOpen && !(isLandscape() && tab == CameraTab.LIVE && (state as? LiveState.Connected)?.video != null),
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
                        onRotate = { edit(scope, it) { session -> session.rotateQuarterTurn() } },
                        torchAvailable = gates?.torch ?: true,
                        pushToTalkAvailable = gates?.pushToTalk ?: true,
                        sirenAvailable = gates?.siren ?: false,
                        sirenOn = siren,
                        onSiren = { on -> siren = on; scope.launch { it.link.setSiren(on) } },
                    )
                },
                onRetry = { attempt++ },
                onSetRemoteAddress = { editingRemote = true },
                onBack = onExit,
            )

            CameraTab.CLIPS -> {
                var clips by remember(open) { mutableStateOf<List<com.homecam.proto.Clip>?>(null) }
                var playing by remember(open) { mutableStateOf<java.io.File?>(null) }
                var playingId by remember(open) { mutableStateOf<String?>(null) }
                playerOpen = playing != null
                var downloading by remember(open) { mutableStateOf<String?>(null) }
                var buckets by remember(open) {
                    mutableStateOf<List<com.homecam.proto.TimelineCount.Bucket>>(emptyList())
                }

                suspend fun refresh() {
                    clips = open?.let { runCatching { it.link.clips().clipsList }.getOrNull() }
                }
                LaunchedEffect(open) {
                    refresh()
                    buckets = open?.let {
                        val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                        runCatching { it.link.timelineCounts(since).bucketsList }.getOrNull()
                    }.orEmpty()
                }

                val nearCeiling = clips?.let { list ->
                    settings?.let { s ->
                        RetentionPlanner.pinnedNearCeiling(
                            list.map { ClipRef(it.id, it.startedAtMs, it.sizeBytes, it.pinned) },
                            s.retention.toRetentionLimits(),
                        )
                    }
                } ?: false

                ClipsScreen(
                    clips = clips,
                    playing = playing,
                    downloadingId = downloading,
                    projectedDays = projectedDays,
                    showBoxes = settings?.boundingBoxOverlay ?: true,
                    loadThumbnail = open?.let { s -> { id -> s.link.thumbnail(id) } },
                    buckets = buckets,
                    pinnedNearCeiling = nearCeiling,
                    onExport = { clip ->
                        edit(scope, open) {
                            downloading = clip.id
                            val file = java.io.File(context.cacheDir, "clips/${clip.id}.mp4")
                            runCatching { if (!file.exists()) it.link.downloadClip(clip.id, file) }
                            com.homecam.app.viewer.ClipExport.toGallery(context, file, "homecam-${clip.id}.mp4")
                            downloading = null
                        }
                    },
                    onPlay = { clip ->
                        edit(scope, open) {
                            downloading = clip.id
                            val file = java.io.File(context.cacheDir, "clips/${clip.id}.mp4")
                            runCatching { if (!file.exists()) it.link.downloadClip(clip.id, file) }
                                .onSuccess { playing = file; playingId = clip.id }
                            downloading = null
                        }
                    },
                    onPin = { clip ->
                        edit(scope, open) { it.link.pinClip(clip.id, !clip.pinned); refresh() }
                    },
                    onDelete = { clip ->
                        edit(scope, open) { it.link.deleteClip(clip.id); refresh() }
                    },
                    onClosePlayer = { playing = null },
                    playingId = playingId,
                )
            }

            CameraTab.SETTINGS -> CameraSettingsScreen(
                settings = settings,
                onArmed = { armed -> edit(scope, open) { settings = it.link.setArmed(armed) } },
                onAlias = { alias -> edit(scope, open) { settings = it.link.setAlias(alias) } },
                onMicrophone = { on ->
                    edit(scope, open) { settings = it.link.setMicrophone(on) }
                },
                onRecordingMode = { mode ->
                    edit(scope, open) { settings = it.link.setRecordingMode(mode) }
                },
                projectedDays = projectedDays,
                autoSpotlightSupported = capabilities?.spotlight ?: true,
                autoSpotlight = settings?.autoSpotlight ?: false,
                onAutoSpotlight = { on ->
                    edit(scope, open) { settings = it.link.setAutoSpotlight(on) }
                },
                reminderEnabled = settings?.detectionReminder?.enabled ?: false,
                onDetectionReminder = { on ->
                    edit(scope, open) { settings = it.link.setDetectionReminder(on, 60) }
                },
                soundDetectionEnabled = settings?.soundDetection?.enabled ?: false,
                onSoundDetection = { on ->
                    edit(scope, open) { settings = it.link.setSoundDetection(on) }
                },
                soundClasses = settings?.soundRecognition?.classesList?.toSet() ?: emptySet(),
                onSoundClass = { soundClass, on ->
                    edit(scope, open) {
                        val next = (settings?.soundRecognition?.classesList ?: emptyList()).toMutableSet()
                        if (on) next.add(soundClass) else next.remove(soundClass)
                        settings = it.link.setSoundRecognition(next.toList())
                    }
                },
                onDetectionMode = { person, vehicle, pet, packages ->
                    edit(scope, open) {
                        settings = it.link.setDetectionMode(person, vehicle, pet, packages)
                    }
                },
                alertsEnabled = alertsOn,
                onAlerts = { on ->
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    edit(scope, open) {
                        it.link.registerAlerts(
                            alertPublicKey = alertKeys.publicKeySpki,
                            topic = alertKeys.topic(context),
                            endpoint = AlertSubscriber.endpointOf(context),
                            enabled = on,
                        )
                        AlertPreferences.setEnabled(context, on)
                        alertsOn = on
                        if (on) AlertSubscriber.start(context) else AlertSubscriber.stop(context)
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
    block: suspend (ViewerSession) -> Unit,
) {
    val open = session ?: return
    scope.launch { runCatching { block(open) }.onFailure { Log.w(TAG, "camera setting change failed", it) } }
}

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
