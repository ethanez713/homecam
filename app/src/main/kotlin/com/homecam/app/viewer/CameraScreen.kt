package com.homecam.app.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import com.homecam.app.AppGraph
import com.homecam.app.alerts.AlertKeys
import com.homecam.app.alerts.AlertPreferences
import com.homecam.app.alerts.AlertSubscriber
import com.homecam.app.camera.ui.AuditScreen
import com.homecam.app.data.CameraEntity
import com.homecam.app.viewer.ui.CameraSettingsScreen
import com.homecam.app.viewer.ui.ClipsScreen
import com.homecam.app.viewer.ui.LiveControls
import com.homecam.app.viewer.ui.LiveScreen
import com.homecam.app.viewer.ui.LiveState
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

private enum class CameraTab(val label: String) {
    LIVE("Live"), CLIPS("Clips"), SETTINGS("Settings"), ACCESS("Access"), LOG("Log")
}

/**
 * Everything the viewer does with one camera, over **one** connection. The tabs are views
 * onto the same authenticated session rather than four screens that each dial the camera
 * — waking a sleeping phone four times to read four things would be its own battery bug.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(node: ViewerNode, camera: CameraEntity, onExit: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val scope = rememberCoroutineScope()

    var attempt by remember(camera.fingerprint) { mutableIntStateOf(0) }
    var tab by remember(camera.fingerprint) { mutableStateOf(CameraTab.LIVE) }
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
    var remoteAddress by remember(camera.fingerprint) {
        mutableStateOf(
            if (camera.remoteHost.isBlank()) ""
            else if (camera.remotePort > 0) "${camera.remoteHost}:${camera.remotePort}"
            else camera.remoteHost
        )
    }

    DisposableEffect(session) { onDispose { session?.close() } }

    LaunchedEffect(camera.fingerprint, attempt) {
        runCatching {
            val address = node.locate(camera)
                ?: error("${camera.alias} is not answering on this network.")
            state = LiveState.Connecting(address.host, address.port)
            val client = node.connect(camera)
            val opened = ViewerSession(context, client)
            session = opened
            state = LiveState.Connected(
                client.verificationPhrase.orEmpty(),
                null,
                runCatching { client.link.runtimeStatus() }.getOrNull(),
                null,
            )
            settings = runCatching { client.link.settings() }.getOrNull()
            capabilities = client.link.capabilities()
            opened.start()
        }.onFailure { state = LiveState.Failed(describeFailure(it)) }
    }

    val open = session
    if (open != null) {
        val video by open.video.collectAsState()
        val presence by open.presence.collectAsState()
        val ended by open.ended.collectAsState()
        LaunchedEffect(video, presence, ended) {
            val connected = state as? LiveState.Connected ?: return@LaunchedEffect
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

    Column(Modifier.fillMaxSize()) {
        if (open != null) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                CameraTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        text = { Text(entry.label) },
                    )
                }
            }
        }

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
                        torchAvailable = gates?.torch ?: true,
                        pushToTalkAvailable = gates?.pushToTalk ?: true,
                        sirenAvailable = gates?.siren ?: false,
                        sirenOn = siren,
                        onSiren = { on -> siren = on; scope.launch { it.link.setSiren(on) } },
                    )
                },
                onBack = onExit,
                onRetry = { attempt++ },
            )

            CameraTab.CLIPS -> {
                var clips by remember(open) { mutableStateOf<List<com.homecam.proto.Clip>?>(null) }
                var playing by remember(open) { mutableStateOf<java.io.File?>(null) }
                var downloading by remember(open) { mutableStateOf<String?>(null) }

                suspend fun refresh() {
                    clips = open?.let { runCatching { it.link.clips().clipsList }.getOrNull() }
                }
                LaunchedEffect(open) { refresh() }

                ClipsScreen(
                    clips = clips,
                    playing = playing,
                    downloadingId = downloading,
                    projectedDays = projectedDays,
                    showBoxes = settings?.boundingBoxOverlay ?: true,
                    loadThumbnail = open?.let { s -> { id -> s.link.thumbnail(id) } },
                    onPlay = { clip ->
                        edit(scope, open) {
                            downloading = clip.id
                            val file = java.io.File(context.cacheDir, "clips/${clip.id}.mp4")
                            runCatching { it.link.downloadClip(clip.id, file) }
                                .onSuccess { playing = file }
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
                onDetectionMode = { person, vehicle, pet, packages ->
                    edit(scope, open) {
                        settings = it.link.setDetectionMode(person, vehicle, pet, packages)
                    }
                },
                alertsEnabled = alertsOn,
                onAlerts = { on ->
                    alertsOn = on
                    edit(scope, open) {
                        it.link.registerAlerts(
                            alertPublicKey = alertKeys.publicKeySpki,
                            topic = alertKeys.topic(context),
                            endpoint = AlertSubscriber.endpointOf(context),
                            enabled = on,
                        )
                        AlertPreferences.setEnabled(context, on)
                        if (on) AlertSubscriber.start(context) else AlertSubscriber.stop(context)
                    }
                },
                remoteAddress = remoteAddress,
                onRemoteAddress = { entered ->
                    val host = entered.substringBefore(':').trim()
                    val port = entered.substringAfter(':', "").toIntOrNull() ?: 0
                    remoteAddress = entered
                    scope.launch { node.setRemoteAddress(camera.fingerprint, host, port) }
                },
                onBack = { tab = CameraTab.LIVE },
            )

            CameraTab.ACCESS -> {
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
                    onBack = { tab = CameraTab.LIVE },
                )
            }

            CameraTab.LOG -> {
                var remote by remember(open) { mutableStateOf<com.homecam.proto.AuditQuery?>(null) }
                LaunchedEffect(open) {
                    remote = open?.let { runCatching { it.link.audit() }.getOrNull() }
                }
                AuditScreen(
                    events = remote?.eventsList.orEmpty(),
                    verification = remote?.let {
                        AuditChain.Verification(it.chainValid, it.firstBrokenSeq)
                    },
                )
            }
        }
    }
}

private fun edit(
    scope: kotlinx.coroutines.CoroutineScope,
    session: ViewerSession?,
    block: suspend (ViewerSession) -> Unit,
) {
    val open = session ?: return
    scope.launch { runCatching { block(open) } }
}
