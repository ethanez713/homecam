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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.activity.compose.BackHandler
import com.homecam.app.AppGraph
import com.homecam.app.alerts.AlertKeys
import com.homecam.app.camera.CameraService
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
import com.homecam.app.ui.SecondLens
import com.homecam.app.ui.formatStamp
import com.homecam.core.lens.LensCatalog
import com.homecam.core.lens.LensSelections
import com.homecam.core.rpc.ControlGating
import com.homecam.core.media.EventSpan
import com.homecam.core.retention.ClipRef
import com.homecam.core.retention.RetentionPlanner
import com.homecam.core.sound.SoundClasses
import com.homecam.app.net.CameraLink
import com.homecam.app.net.ClipHistoryPage
import com.homecam.proto.Capabilities
import com.homecam.proto.Clip
import com.homecam.proto.SettingsSnapshot
import com.homecam.proto.Viewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    var viewers by remember(camera.fingerprint, attempt, foreground) { mutableStateOf<List<Viewer>?>(null) }
    var torch by remember(camera.fingerprint, attempt, foreground) { mutableStateOf(false) }
    var mic by remember(camera.fingerprint, attempt, foreground) { mutableStateOf(false) }
    var siren by remember(camera.fingerprint, attempt, foreground) { mutableStateOf(false) }
    var zoom by remember(camera.fingerprint, attempt, foreground) { mutableFloatStateOf(0f) }
    var zoomByLens by remember(camera.fingerprint, attempt, foreground) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var liveQuality by remember(camera.fingerprint) { mutableIntStateOf(0) }
    val selectedLens = settings?.let(LensSelections::primaryOf) ?: LensCatalog.DEFAULT
    val lensPairs = capabilities?.concurrentLensesList.orEmpty()
    val secondaryLens = settings?.lens?.secondaryList?.firstOrNull()
    val partnerId = LensSelections.partner(selectedLens, lensPairs, secondaryLens)
    val lensInfo = capabilities?.lensesList?.firstOrNull { it.id == selectedLens }
    var negotiatedLenses by remember(camera.fingerprint, attempt, foreground) { mutableStateOf<List<String>?>(null) }
    var phrase by remember(camera.fingerprint, attempt, foreground) { mutableStateOf("") }
    var via by remember(camera.fingerprint, attempt, foreground) { mutableStateOf("") }
    var mediaStarted by remember(camera.fingerprint, attempt, foreground) { mutableStateOf(false) }
    // Survives a reconnect: Turn off asks the next connection to clear privacy before watching.
    var disarmPrivacy by remember(camera.fingerprint) { mutableStateOf(false) }
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
            val status = runCatching { client.link.runtimeStatus() }.getOrNull()
            val loaded = runCatching { client.link.settings() }.getOrNull()
            settings = loaded
            loaded?.zoomInLock?.let { lock ->
                zoomByLens = lock.zoomByLensMap.toMap()
                if (lock.enabled) zoom = zoomByLens[LensSelections.primaryOf(loaded)] ?: 0f
            }
            capabilities = client.link.capabilities()
            phrase = client.verificationPhrase.orEmpty()
            via = if (address.host == camera.remoteHost) "Remote" else "Home network"
            val turningOff = disarmPrivacy
            if (turningOff) {
                settings = client.link.setPrivacyMode(false)
                disarmPrivacy = false
            }
            // Status is what the camera is doing now. A settings read can lag behind it.
            val privateNow = !turningOff && (status?.privacyMode == true || settings?.privacyMode == true)
            if (privateNow) {
                state = LiveState.Privacy
                return@runCatching
            }
            val fresh = if (turningOff) runCatching { client.link.runtimeStatus() }.getOrNull() else status
            state = LiveState.Connected(phrase, null, fresh, null, via)
            mediaStarted = true
            val startingLenses = settings?.lens?.secondaryList?.toList()
            opened.start()
            if (liveQuality != 0) opened.setLiveResolution(liveQuality)
            negotiatedLenses = startingLenses ?: emptyList()
        }.onFailure {
            Log.w(TAG, "live session to ${camera.alias} failed at ${state::class.simpleName}", it)
            mediaStarted = false
            state = if (it.message?.contains(CameraService.PRIVACY_DETAIL) == true) {
                LiveState.Privacy
            } else {
                LiveState.Failed(describeFailure(it))
            }
        }
    }

    val open = session
    if (open != null) {
        val videos by open.videos.collectAsState()
        val presence by open.presence.collectAsState()
        val ended by open.ended.collectAsState()
        LaunchedEffect(videos, presence, ended, selectedLens) {
            if (ended?.second == CameraService.PRIVACY_DETAIL) {
                mediaStarted = false
                state = LiveState.Privacy
                return@LaunchedEffect
            }
            val connected = state as? LiveState.Connected ?: return@LaunchedEffect
            ended?.let { Log.w(TAG, "live session to ${camera.alias} ended: $it") }
            val picture = videos[selectedLens] ?: videos[""] ?: videos.values.firstOrNull()
            state = ended?.let { LiveState.Failed(describeBye(it.first, it.second)) }
                ?: connected.copy(presence = presence, video = picture, videos = videos)
        }
    }

    LaunchedEffect(open, settings?.lens?.secondaryList) {
        val session = open ?: return@LaunchedEffect
        snapshotFlow { negotiatedLenses }.first { it != null }
        val baseline = negotiatedLenses ?: return@LaunchedEffect
        val current = settings?.lens?.secondaryList?.toList() ?: return@LaunchedEffect
        if (baseline == current) return@LaunchedEffect
        negotiatedLenses = current
        runCatching {
            session.restart()
            if (liveQuality != 0) session.setLiveResolution(liveQuality)
        }.onFailure { Log.w(TAG, "live session restart failed", it) }
    }

    // The picture of the camera's health is only as good as its last reading, and a pause or a
    // timed revert can happen while this screen is open.
    LaunchedEffect(open) {
        val watching = open ?: return@LaunchedEffect
        val link = watching.link
        while (true) {
            kotlinx.coroutines.delay(if (state is LiveState.Privacy) PRIVACY_STATUS_REFRESH_MS else STATUS_REFRESH_MS)
            val fresh = runCatching { link.runtimeStatus() }.getOrNull() ?: continue
            when {
                fresh.privacyMode -> {
                    if (state !is LiveState.Privacy) {
                        mediaStarted = false
                        state = LiveState.Privacy
                    }
                }
                state is LiveState.Privacy && !mediaStarted && watching.ended.value == null -> {
                    mediaStarted = true
                    state = LiveState.Connected(phrase, null, fresh, null, via)
                    val lenses = settings?.lens?.secondaryList?.toList()
                    runCatching {
                        watching.start()
                        negotiatedLenses = lenses ?: emptyList()
                    }.onFailure { error ->
                        Log.w(TAG, "live session to ${camera.alias} failed", error)
                        mediaStarted = false
                        state = if (error.message?.contains(CameraService.PRIVACY_DETAIL) == true) {
                            LiveState.Privacy
                        } else {
                            LiveState.Failed(describeFailure(error))
                        }
                    }
                }
                else -> (state as? LiveState.Connected)?.let { state = it.copy(status = fresh) }
            }
            runCatching { link.settings() }.getOrNull()?.let { settings = it }
        }
    }

    LaunchedEffect(settings?.armed, settings?.recordingMode) {
        settings?.let { node.rememberState(camera.fingerprint, it.armed, it.recordingMode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
    }

    // One walk per open session, shared with the Clips tab. The estimate has to see pinned
    // bytes older than a single page, and a second walk would repeat every page (§5).
    var clipHistory by remember(open) { mutableStateOf<List<Clip>?>(null) }
    var eventSpans by remember(open) { mutableStateOf<List<EventSpan>>(emptyList()) }
    var projectedDays by remember(open) { mutableStateOf<Double?>(null) }
    val historyGate = remember(open) { Mutex() }
    fun projectRetention(history: List<Clip>?) {
        val current = settings
        projectedDays = if (open == null || current == null || history == null) {
            null
        } else {
            RetentionPlanner.projectedRetentionDays(
                history.map { ClipRef(it.id, it.startedAtMs, it.sizeBytes, it.pinned) },
                current.retention.toRetentionLimits(),
                System.currentTimeMillis(),
            )
        }
    }
    suspend fun reloadClipHistory() {
        val link = open?.link ?: return
        historyGate.withLock {
            val seen = mutableSetOf<String>()
            var received = false
            try {
                link.forEachClipPage { page ->
                    currentCoroutineContext().ensureActive()
                    received = true
                    seen += page.clips.map { it.id }
                    clipHistory = mergeClipHistory(clipHistory.orEmpty(), page.clips)
                    eventSpans = (eventSpans + spansFor(link, page)).distinct()
                    projectRetention(clipHistory)
                }
                if (!received) {
                    clipHistory = emptyList()
                    eventSpans = emptyList()
                } else {
                    clipHistory = clipHistory.orEmpty().filter { it.id in seen }
                    eventSpans = eventSpans.filter { it.clipId in seen }.distinct()
                }
                projectRetention(clipHistory)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Leave the pages already shown. Clearing the list would also drop the
                // playhead, which lives as long as that list stays on screen.
                Log.w(TAG, "clip history", error)
            }
        }
    }
    LaunchedEffect(open) {
        if (open != null) reloadClipHistory()
    }
    LaunchedEffect(settings?.recordingMode, settings?.retention) {
        projectRetention(clipHistory)
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

    suspend fun turnPrivacyOff() {
        val opened = session
        if (opened == null || opened.ended.value != null) {
            disarmPrivacy = true
            attempt++
            return
        }
        runCatching {
            settings = opened.link.setPrivacyMode(false)
            if (!mediaStarted) {
                mediaStarted = true
                val fresh = runCatching { opened.link.runtimeStatus() }.getOrNull()
                state = LiveState.Connected(phrase, null, fresh, null, via)
                val lenses = settings?.lens?.secondaryList?.toList()
                opened.start()
                negotiatedLenses = lenses ?: emptyList()
            }
        }.onFailure {
            Log.w(TAG, "could not turn privacy mode off", it)
            mediaStarted = false
            disarmPrivacy = true
            attempt++
        }
    }

    BackHandler { if (tab == CameraTab.LIVE) onExit() else tab = CameraTab.LIVE }

    CameraShell(
        alias = settings?.alias ?: camera.alias,
        status = when (val now = state) {
            is LiveState.Locating -> "Finding the camera"
            is LiveState.Connecting -> "Connecting"
            is LiveState.Connected -> "Connected · ${now.via.lowercase()}"
            is LiveState.Failed -> "Not connected"
            is LiveState.Privacy -> "Privacy mode"
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
                        onTorch = { on -> torch = on; scope.request("torch") { it.setTorch(on) } },
                        onMic = { on -> mic = on; scope.request("push to talk") { it.setPushToTalk(on) } },
                        onZoom = { value ->
                            zoom = value
                            val id = settings?.let(LensSelections::primaryOf)
                            if (id != null && settings?.zoomInLock?.enabled == true) {
                                zoomByLens = zoomByLens + (id to value)
                            }
                            scope.request("zoom") { it.setZoom(value) }
                        },
                        liveQuality = liveQuality,
                        onLiveQuality = { height ->
                            liveQuality = height
                            scope.request("live quality") { it.setLiveResolution(height) }
                        },
                        lenses = if (gates?.lensSwitch == false) emptyList() else capabilities?.lensesList.orEmpty(),
                        lens = selectedLens,
                        onLens = { id ->
                            edit(scope, it, say) { session ->
                                settings = session.link.setLens(
                                    LensSelections.withSecondary(id, secondaryLens, lensPairs),
                                )
                                val lock = settings?.zoomInLock
                                val remembered = zoomByLens[id] ?: lock?.zoomByLensMap?.get(id)
                                zoom = if (lock?.enabled == true) remembered ?: 0f else 0f
                                torch = false
                                if (lock?.enabled == true) session.setZoom(zoom)
                            }
                        },
                        secondaryLens = secondaryLens,
                        secondLens = partnerId?.let { partner ->
                            SecondLens(
                                partnerId = partner,
                                on = settings?.lens?.secondaryList?.contains(partner) == true,
                                onChange = { on ->
                                    edit(scope, it, say) { session ->
                                        settings = session.link.setLens(
                                            LensSelections.withSecondary(
                                                selectedLens,
                                                if (on) partner else null,
                                                lensPairs,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                        onRotate = {
                            edit(scope, it, say) { session ->
                                session.rotateQuarterTurn()
                                runCatching { settings = session.link.settings() }
                            }
                        },
                        onSnapshot = {
                            edit(scope, it, say) { session ->
                                val still = session.link.snapshot()
                                val saved = com.homecam.app.viewer.ClipExport.imageToGallery(
                                    context, still.jpeg.toByteArray(), "homecam-${System.currentTimeMillis()}.jpg",
                                )
                                say(if (saved != null) "Snapshot saved to the gallery." else "The snapshot could not be saved.")
                            }
                        },
                        torchAvailable = (gates?.torch ?: true) && (lensInfo?.torch ?: true),
                        pushToTalkAvailable = gates?.pushToTalk ?: true,
                        sirenAvailable = gates?.siren ?: false,
                        sirenOn = siren,
                        onSiren = { on ->
                            siren = on
                            scope.request("siren") { it.link.setSiren(on) }
                        },
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
                onTurnOffPrivacy = { scope.launch { turnPrivacyOff() } },
                onSetRemoteAddress = { editingRemote = true },
                onBack = onExit,
                onFocus = { x, y ->
                    open?.let { session ->
                        scope.request("focus") { session.setFocus(x, y) }
                    }
                },
                onShownLenses = showing@{ shown ->
                    val session = open ?: return@showing
                    scope.request("live lenses") { session.setShownLenses(shown) }
                },
            )

            CameraTab.CLIPS -> {
                var playing by remember(open) { mutableStateOf<java.io.File?>(null) }
                var playingId by remember(open) { mutableStateOf<String?>(null) }
                var downloading by remember(open) { mutableStateOf<String?>(null) }
                var downloadProgress by remember(open) { mutableStateOf<com.homecam.core.media.DownloadProgress?>(null) }
                var speed by remember { mutableFloatStateOf(1f) }
                val transfers = remember(open) { ClipTransfers(scope) }
                val arrived = remember(open) { java.util.concurrent.ConcurrentHashMap<String, Long>() }
                val waiting = remember(open) { WaitingClip() }
                var playSerial by remember(open) { mutableIntStateOf(0) }
                DisposableEffect(transfers) { onDispose { transfers.cancelPrefetch() } }

                val nearCeiling = clipHistory?.let { list ->
                    settings?.let { s ->
                        RetentionPlanner.pinnedNearCeiling(
                            list.map { ClipRef(it.id, it.startedAtMs, it.sizeBytes, it.pinned) },
                            s.retention.toRetentionLimits(),
                        )
                    }
                } ?: false

                suspend fun fetch(session: ViewerSession, clip: com.homecam.proto.Clip, prefetch: Boolean): java.io.File {
                    val file = java.io.File(context.cacheDir, "clips/${clip.id}.mp4")
                    val cached = file.isFile && file.length() > 0L &&
                        (clip.sizeBytes <= 0L || file.length() == clip.sizeBytes)
                    if (cached) return file
                    file.delete()
                    val part = java.io.File(file.parentFile, "${file.name}.part")
                    val started = System.nanoTime()
                    try {
                        session.link.downloadClip(clip.id, part) { received ->
                            arrived[clip.id] = received
                            if (waiting.id == clip.id) {
                                downloadProgress = com.homecam.core.media.DownloadProgress(received, clip.sizeBytes)
                            }
                        }
                        if (clip.sizeBytes > 0L && part.length() != clip.sizeBytes) {
                            throw java.io.IOException(
                                "clip ${clip.id} is ${part.length()} bytes, expected ${clip.sizeBytes}",
                            )
                        }
                        if (!part.renameTo(file)) throw java.io.IOException("could not store clip ${clip.id}")
                        graph.viewerDiagnostics.clipFetch(
                            clip.id,
                            file.length(),
                            (System.nanoTime() - started) / 1_000_000L,
                            prefetch,
                        )
                        return file
                    } catch (e: Throwable) {
                        part.delete()
                        throw e
                    } finally {
                        arrived.remove(clip.id)
                        if (waiting.id == clip.id) downloadProgress = null
                    }
                }

                fun showProgress(clip: com.homecam.proto.Clip) {
                    waiting.id = clip.id
                    downloading = clip.id
                    val got = arrived[clip.id]
                    downloadProgress = got?.let { com.homecam.core.media.DownloadProgress(it, clip.sizeBytes) }
                }

                ClipsScreen(
                    clips = clipHistory,
                    eventSpans = eventSpans,
                    playing = playing,
                    playingId = playingId,
                    speed = speed,
                    onSpeed = { speed = it },
                    downloadingId = downloading,
                    downloadProgress = downloadProgress,
                    projectedDays = projectedDays,
                    showBoxes = settings?.boundingBoxOverlay ?: true,
                    loadThumbnail = open?.let { s -> { id -> s.link.thumbnail(id) } },
                    pinnedNearCeiling = nearCeiling,
                    onPlay = play@{ clip ->
                        if (playingId == clip.id) playing = null
                        val session = open ?: return@play say("Not connected to the camera. Nothing was changed.")
                        val serial = ++playSerial
                        scope.launch {
                            showProgress(clip)
                            try {
                                val file = transfers.forPlay(clip.id) { fetch(session, clip, prefetch = false) }
                                if (serial == playSerial) {
                                    playing = file
                                    playingId = clip.id
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            } finally {
                                if (serial == playSerial && waiting.id == clip.id) {
                                    waiting.id = null
                                    downloading = null
                                    downloadProgress = null
                                }
                            }
                        }
                    },
                    onPrefetch = prefetch@{ clip ->
                        val session = open ?: return@prefetch
                        // A seek that has not started playing still reports the old clip's next one.
                        val waitingFor = waiting.id
                        if (waitingFor != null && waitingFor != playingId) return@prefetch
                        transfers.prefetch(clip.id) { fetch(session, clip, prefetch = true) }
                    },
                    onClipUnplayable = { id, fileBytes, expectedBytes, what, extra ->
                        graph.viewerDiagnostics.clipUnplayable(id, fileBytes, expectedBytes, what, extra)
                    },
                    onExport = { chosen ->
                        edit(scope, open, say) { session ->
                            try {
                                chosen.forEach { clip ->
                                    showProgress(clip)
                                    try {
                                        val file = transfers.forExport(clip.id) { fetch(session, clip, prefetch = false) }
                                        com.homecam.app.viewer.ClipExport.toGallery(context, file, "homecam-${clip.id}.mp4")
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                    }
                                }
                                say(if (chosen.size == 1) "Saved to the gallery." else "Saved ${chosen.size} clips to the gallery.")
                            } finally {
                                if (chosen.any { it.id == waiting.id }) {
                                    waiting.id = null
                                    downloading = null
                                    downloadProgress = null
                                }
                            }
                        }
                    },
                    onPin = { clip ->
                        edit(scope, open, say) { it.link.pinClip(clip.id, !clip.pinned); reloadClipHistory() }
                    },
                    onDelete = { doomed ->
                        edit(scope, open, say) {
                            doomed.forEach { clip -> it.link.deleteClip(clip.id) }
                            if (doomed.any { clip -> clip.id == playingId }) { playing = null; playingId = null }
                            reloadClipHistory()
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
                onAutoRotate = { on ->
                    edit(scope, open, say) {
                        settings = it.link.setRotation(on, settings?.rotation?.degrees ?: 0)
                    }
                },
                onOsd = { on -> edit(scope, open, say) { settings = it.link.setOsd(on) } },
                lowLightSupported = capabilities?.lowLight == true,
                onLowLight = { on -> edit(scope, open, say) { settings = it.link.setLowLight(on) } },
                onZoomInLock = { on ->
                    edit(scope, open, say) { session ->
                        if (on) {
                            val id = settings?.let(LensSelections::primaryOf)
                            if (id != null) zoomByLens = zoomByLens + (id to zoom)
                        }
                        val next = session.link.setZoomInLock(on)
                        settings = next
                        zoomByLens = next.zoomInLock.zoomByLensMap + zoomByLens
                    }
                },
                gestureUnlockSupported = capabilities?.gestureUnlock == true,
                onGestureUnlock = { enabled, clearLockout ->
                    edit(scope, open, say) { settings = it.link.setGestureUnlock(enabled, clearLockout) }
                },
                onUnlockCode = { signs ->
                    edit(scope, open, say) { settings = it.link.setUnlockCode(signs) }
                },
                onAutoPowerSaving = { on ->
                    edit(scope, open, say) { settings = it.link.setAutoPowerSaving(on) }
                },
                onPrivacyMode = { on ->
                    if (!on && state is LiveState.Privacy) {
                        scope.launch { turnPrivacyOff() }
                    } else {
                        edit(scope, open, say) { settings = it.link.setPrivacyMode(on) }
                    }
                },
                onRecordingMode = { mode ->
                    edit(scope, open, say) { settings = it.link.setRecordingMode(mode) }
                },
                qualityTiers = capabilities?.qualityTiersList
                    ?: com.homecam.core.media.CaptureQuality.profiles.map { it.tier },
                onQuality = { tier ->
                    edit(scope, open, say) { settings = it.link.setQuality(tier) }
                },
                projectedDays = projectedDays,
                protection = settings?.takeIf { capabilities?.hardwareProtection == true }
                    ?.let { HealthProtocol.configOf(it) },
                onProtection = { protection ->
                    edit(scope, open, say) { settings = it.link.setHardwareProtection(protection) }
                },
                autoSpotlightSupported = (capabilities?.spotlight ?: true) && (lensInfo?.torch ?: true),
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
                verificationPhrase = phrase.ifBlank { (state as? LiveState.Connected)?.phrase.orEmpty() },
                fingerprint = camera.fingerprint,
            )
        }
    }
}

private suspend fun spansFor(link: CameraLink, page: ClipHistoryPage): List<EventSpan> =
    try {
        link.eventSpans(page.oldestStartedAtMs, page.newestStartedAtMs)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "event spans", error)
        emptyList()
    }

private fun mergeClipHistory(existing: List<Clip>, page: List<Clip>): List<Clip> {
    val incoming = page.associateBy { it.id }
    val known = existing.map { it.id }.toSet()
    return existing.map { clip -> incoming[clip.id] ?: clip } + page.filter { it.id !in known }
}

/** A camera call whose failure the screen has nothing to say about; an uncaught one would crash the app. */
private fun kotlinx.coroutines.CoroutineScope.request(what: String, call: suspend () -> Unit) {
    launch { runCatching { call() }.onFailure { Log.w(TAG, what, it) } }
}

private fun edit(
    scope: kotlinx.coroutines.CoroutineScope,
    session: ViewerSession?,
    say: (String) -> Unit,
    block: suspend (ViewerSession) -> Unit,
) {
    val open = session ?: return say("Not connected to the camera. Nothing was changed.")
    scope.launch {
        try {
            block(open)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "camera request failed", e)
            say("The camera did not answer. Nothing was changed.")
        }
    }
}

private const val TOAST_MS = 2_800L
private const val STATUS_REFRESH_MS = 20_000L
private const val PRIVACY_STATUS_REFRESH_MS = 2_000L

/** The clip whose download the spinner follows. A prefetch leaves it unset until a play or export joins. */
private class WaitingClip {
    @Volatile var id: String? = null
}

private class ClipTransfer(
    val result: CompletableDeferred<java.io.File>,
    val job: Job,
)

/**
 * One download per clip id. At most one of them is a prefetch: a play of any other clip
 * cancels it (and its `.part`), and a play of the same clip waits on the download already running.
 */
private class ClipTransfers(private val scope: kotlinx.coroutines.CoroutineScope) {
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, ClipTransfer>()
    @Volatile private var prefetchJob: Job? = null
    @Volatile private var prefetchId: String? = null

    fun cancelPrefetch() {
        val job = prefetchJob ?: return
        prefetchJob = null
        prefetchId = null
        job.cancel()
    }

    fun prefetch(clipId: String, download: suspend () -> java.io.File) {
        val existing = inFlight[clipId]
        if (existing != null && existing.job.isActive) return
        if (prefetchId != null && prefetchId != clipId) cancelPrefetch()
        start(clipId, prefetch = true, download)
    }

    suspend fun forPlay(clipId: String, download: suspend () -> java.io.File): java.io.File {
        if (prefetchId != null && prefetchId != clipId) cancelPrefetch()
        return await(clipId, prefetch = false, download)
    }

    /** Joins a download already running for this clip and does not cancel a prefetch of another. */
    suspend fun forExport(clipId: String, download: suspend () -> java.io.File): java.io.File =
        await(clipId, prefetch = false, download)

    private suspend fun await(
        clipId: String,
        prefetch: Boolean,
        download: suspend () -> java.io.File,
    ): java.io.File {
        val existing = inFlight[clipId]
        if (existing != null && existing.job.isActive) return existing.result.await()
        return start(clipId, prefetch, download).result.await()
    }

    private fun start(clipId: String, prefetch: Boolean, download: suspend () -> java.io.File): ClipTransfer {
        val previous = inFlight[clipId]
        val result = CompletableDeferred<java.io.File>()
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                previous?.job?.join()
                result.complete(download())
            } catch (e: CancellationException) {
                result.cancel(e)
                throw e
            } catch (e: Exception) {
                result.completeExceptionally(e)
                if (prefetch) Log.w(TAG, "clip prefetch $clipId", e)
            }
        }
        val transfer = ClipTransfer(result, job)
        inFlight[clipId] = transfer
        if (prefetch) {
            prefetchId = clipId
            prefetchJob = job
        }
        job.invokeOnCompletion { cause ->
            if (!result.isCompleted) {
                when (cause) {
                    is CancellationException -> result.cancel(cause)
                    null -> result.completeExceptionally(IllegalStateException("clip download ended without a file"))
                    else -> result.completeExceptionally(cause)
                }
            }
            if (inFlight.remove(clipId, transfer) && prefetchJob === job) {
                prefetchJob = null
                prefetchId = null
            }
        }
        job.start()
        return transfer
    }
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
