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
import com.homecam.app.camera.ui.AuditScreen
import com.homecam.app.data.CameraEntity
import com.homecam.app.viewer.ui.CameraSettingsScreen
import com.homecam.app.viewer.ui.ClipsScreen
import com.homecam.app.viewer.ui.LiveControls
import com.homecam.app.viewer.ui.LiveScreen
import com.homecam.app.viewer.ui.LiveState
import com.homecam.app.viewer.ui.RemoteRosterScreen
import com.homecam.core.audit.AuditChain
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
    var viewers by remember(camera.fingerprint, attempt) { mutableStateOf<List<Viewer>?>(null) }
    var torch by remember(camera.fingerprint, attempt) { mutableStateOf(false) }
    var mic by remember(camera.fingerprint, attempt) { mutableStateOf(false) }
    var zoom by remember(camera.fingerprint, attempt) { mutableFloatStateOf(0f) }

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
                    LiveControls(
                        torchOn = torch,
                        micOn = mic,
                        zoom = zoom,
                        onTorch = { on -> torch = on; scope.launch { it.setTorch(on) } },
                        onMic = { on -> mic = on; scope.launch { it.setPushToTalk(on) } },
                        onZoom = { value -> zoom = value; scope.launch { it.setZoom(value) } },
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
                    projectedDays = null,
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
                onDetectionMode = { person, vehicle, pet, packages ->
                    edit(scope, open) {
                        settings = it.link.setDetectionMode(person, vehicle, pet, packages)
                    }
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
