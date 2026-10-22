package com.homecam.app.debug

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.homecam.app.camera.ui.AuditScreen
import com.homecam.app.camera.ui.CameraHomeScreen
import com.homecam.app.camera.ui.PairingScreen
import com.homecam.app.camera.ui.RosterScreen
import com.homecam.app.data.CameraEntity
import com.homecam.app.data.ViewerEntity
import com.homecam.app.ui.NetworkActivityScreen
import com.homecam.app.ui.RolePickerScreen
import com.homecam.app.ui.formatStamp
import com.homecam.app.ui.theme.HomecamTheme
import com.homecam.app.ui.viewerEgress
import com.homecam.app.viewer.ui.CameraSettingsScreen
import com.homecam.app.viewer.ui.CameraShell
import com.homecam.app.viewer.ui.CameraTab
import com.homecam.app.viewer.ui.ClipsScreen
import com.homecam.app.viewer.ui.LiveControls
import com.homecam.app.viewer.ui.LiveScreen
import com.homecam.app.viewer.ui.LiveState
import com.homecam.app.viewer.ui.PairConfirmScreen
import com.homecam.app.viewer.ui.PairState
import com.homecam.app.viewer.ui.RemoteRosterScreen
import com.homecam.app.viewer.ui.ViewerHomeScreen
import com.homecam.core.audit.AuditChain
import com.homecam.proto.AuditEvent
import com.homecam.proto.Clip
import com.homecam.proto.DetectionMode
import com.homecam.proto.RecordingMode
import com.homecam.proto.RuntimeStatus
import com.homecam.proto.SettingsSnapshot
import com.homecam.proto.SoundDetection
import com.homecam.proto.SoundRecognition
import com.homecam.proto.Viewer
import java.io.ByteArrayOutputStream

/**
 * Every screen with sample data and no camera behind it, for looking at the UI on an
 * emulator: `am start -n com.homecam.app/.debug.GalleryActivity --es screen clips --ez dark true`.
 */
class GalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scene = assets.open("gallery/front-door.jpg").use(BitmapFactory::decodeStream)
        val screen = intent.getStringExtra("screen") ?: "viewer_home"
        val dark = intent.getBooleanExtra("dark", false)
        setContent {
            HomecamTheme(dark = dark) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().safeDrawingPadding()) { Screen(screen) } }
            }
        }
    }
}

private const val NOW = 1_790_917_200_000L
private const val MINUTE = 60_000L

private val cameras = listOf(
    CameraEntity("Y1TM-4JVY-9K8F", ByteArray(0), "Front door", "192.168.0.18", 8443, "100.64.0.9", 0, NOW - 90 * MINUTE, System.currentTimeMillis() - 2 * MINUTE, lastArmed = 1, lastRecordingMode = "Continuous"),
    CameraEntity("Q7RD-2MXA-HH3C", ByteArray(0), "Garage", "192.168.0.31", 8443, "", 0, NOW - 9000 * MINUTE, System.currentTimeMillis() - 3 * 24 * 60 * MINUTE, lastArmed = 0, lastRecordingMode = "Detection"),
)

private val settings = SettingsSnapshot.newBuilder()
    .setArmed(true).setAlias("Front door").setMicrophone(true)
    .setDetectionMode(DetectionMode.newBuilder().setPerson(true).setVehicle(true).setPet(true))
    .setSoundDetection(SoundDetection.newBuilder().setEnabled(true))
    .setSoundRecognition(SoundRecognition.newBuilder().addClasses("alarm").addClasses("glass"))
    .setRecordingMode(RecordingMode.CONTINUOUS)
    .build()

private val status = RuntimeStatus.newBuilder()
    .setBatteryPercent(85f).setCharging(true).setBatteryTempC(37.2f)
    .setFreeBytes(84_000_000_000).setClipBytes(360_000_000).setArmed(true).setViewersWithAccess(2)
    .build()

private fun clip(id: String, minutesAgo: Long, seconds: Int, megabytes: Double, vararg labels: String, pinned: Boolean = false) =
    Clip.newBuilder().setId(id).setStartedAtMs(System.currentTimeMillis() - minutesAgo * MINUTE)
        .setDurationMs(seconds * 1000).setSizeBytes((megabytes * 1024 * 1024).toLong())
        .setWidth(1920).setHeight(1080).addAllLabels(labels.toList()).setPinned(pinned).build()

private val clips = buildList {
    repeat(9) { add(clip("run-a-$it", 4L + it * 2, 120, 11.5 + it % 3, *(if (it == 3) arrayOf("person") else emptyArray()))) }
    add(clip("solo-1", 95, 14, 5.1, "person", pinned = true))
    add(clip("solo-2", 190, 19, 11.1, "person", "pet"))
    repeat(5) { add(clip("run-b-$it", 600L + it * 2, 120, 12.0)) }
    add(clip("y-1", 1500, 17, 7.4, "sound"))
    add(clip("y-2", 1620, 31, 9.8, "vehicle"))
    repeat(4) { add(clip("run-c-$it", 1700L + it * 2, 120, 10.2)) }
}

private fun event(seq: Long, minutesAgo: Long, kind: AuditEvent.Kind, detail: String = "", ip: String = "192.168.0.248", who: Boolean = true) =
    AuditEvent.newBuilder().setSeq(seq).setAtMs(System.currentTimeMillis() - minutesAgo * MINUTE).setKind(kind)
        .setViewerLabel(if (who) "Samsung SM-S938U" else "").setViewerFingerprint(if (who) "KQSP-NHPF-2NNZ" else "")
        .setSourceIp(if (who) ip else "").setDetail(detail).build()

private val events = listOf(
    event(40, 1, AuditEvent.Kind.SESSION_OPENED, "live video", "100.64.0.21"),
    event(39, 1, AuditEvent.Kind.SESSION_OPENED, "", "100.64.0.21"),
    event(38, 12, AuditEvent.Kind.DETECTION, "person 71%", who = false),
    event(37, 20, AuditEvent.Kind.SESSION_CLOSED, "72s · connection closed"),
    event(36, 21, AuditEvent.Kind.SESSION_OPENED, "live video"),
    event(35, 44, AuditEvent.Kind.SETTINGS_CHANGED, "armed"),
    event(34, 50, AuditEvent.Kind.CLIP_VIEWED, "5ad532d3"),
    event(33, 300, AuditEvent.Kind.CONNECTION_REJECTED, "unknown key", "192.168.0.77", who = false),
    event(32, 1500, AuditEvent.Kind.PAIRING_COMPLETED),
    event(31, 1501, AuditEvent.Kind.PAIRING_STARTED, "192.168.0.18:8443", who = false),
    event(30, 1600, AuditEvent.Kind.CAMERA_STARTED, "port 8443", who = false),
)

private val viewers = listOf(
    Viewer.newBuilder().setFingerprint("KQSP-NHPF-2NNZ").setLabel("Samsung SM-S938U").setPairedAtMs(NOW - 60 * MINUTE)
        .setLastSeenMs(System.currentTimeMillis() - MINUTE).setSessions30D(24).build(),
    Viewer.newBuilder().setFingerprint("B9JD-P7DE-K2KW").setLabel("Pixel 8").setPairedAtMs(NOW - 9000 * MINUTE)
        .setLastSeenMs(System.currentTimeMillis() - 2000 * MINUTE).setSessions30D(3).build(),
)

// A doorstep photo standing in for the camera feed; see THIRD_PARTY.md for its license.
private lateinit var scene: Bitmap

/** The scene, cropped a little differently per clip so the list does not repeat one frame. */
private fun thumbnail(seed: String): ByteArray {
    val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    val inset = seed.hashCode() and 63
    val source = Rect(inset, inset * 9 / 16, scene.width - (63 - inset), scene.height - (63 - inset) * 9 / 16)
    Canvas(bitmap).drawBitmap(scene, source, Rect(0, 0, 640, 360), Paint(Paint.FILTER_BITMAP_FLAG))
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
}

@Composable
private fun Scene() {
    val image = remember { scene.asImageBitmap() }
    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
}

private val pausedStatus = status.toBuilder()
    .setCharging(false).setBatteryPercent(62f).setDrainPercentPerHour(9f).setBatteryTempC(45.3f)
    .setThermalStatus(RuntimeStatus.Thermal.THERMAL_SEVERE)
    .setCap(RuntimeStatus.Cap.CAP_NO_CONTINUOUS).setCapReason(RuntimeStatus.CapReason.REASON_THERMAL)
    .setCapSinceMs(System.currentTimeMillis() - 12 * MINUTE)
    .setRequestedRecordingMode(RecordingMode.CONTINUOUS).setEffectiveRecordingMode(RecordingMode.DETECTION)
    .setRecordingRevertAtMs(System.currentTimeMillis() + 50 * MINUTE).setRecordingRevertTo(RecordingMode.DETECTION)
    .build()

private val sampleLenses = listOf(
    com.homecam.proto.LensInfo.newBuilder().setId("back-wide").setZoomMin(1f).setZoomMax(8f).setTorch(true).build(),
    com.homecam.proto.LensInfo.newBuilder().setId("back-ultrawide").setZoomMin(1f).setZoomMax(8f).setTorch(true).build(),
    com.homecam.proto.LensInfo.newBuilder().setId("front").setZoomMin(1f).setZoomMax(4f).build(),
)

private fun controls(siren: Boolean = false, from: RuntimeStatus = status) = LiveControls(
    torchOn = false, micOn = false, zoom = 0.25f, onTorch = {}, onMic = {}, onZoom = {},
    sirenAvailable = true, sirenOn = siren,
    lenses = sampleLenses, lens = "back-wide", onLens = {},
    recording = com.homecam.app.ui.RecordingPanelState(
        mode = from.requestedRecordingMode.takeIf { from.cap != RuntimeStatus.Cap.CAP_NONE } ?: RecordingMode.DETECTION,
        readout = com.homecam.core.health.HealthReadout.recording(from, ::formatStamp),
        projectedDays = 14.0,
        onChange = { _, _ -> },
    ),
    health = com.homecam.core.health.HealthReadout.card(from, null, 30),
)

@Composable
private fun Shell(tab: CameraTab, armed: Boolean = true, content: @Composable () -> Unit) {
    var current by remember { mutableStateOf(tab) }
    CameraShell(
        "Front door", "Connected · home network", armed, current, { current = it }, {},
        chrome = !(com.homecam.app.ui.kit.isLandscape() && current != CameraTab.SETTINGS), content = content,
    )
}

@Composable
private fun Screen(name: String) {
    when (name) {
        "role" -> RolePickerScreen {}
        "viewer_home" -> ViewerHomeScreen(cameras, {}, {}, {}, {}, {}, starred = "Y1TM-4JVY-9K8F")
        "viewer_home_empty" -> ViewerHomeScreen(emptyList(), {}, {}, {}, {}, {})
        "live" -> Shell(CameraTab.LIVE) {
            LiveScreen(LiveState.Connected("amber-otter-seven", null, status, null, "Home network"), controls(), {}) { Scene() }
        }
        "live_paused" -> Shell(CameraTab.LIVE) {
            LiveScreen(LiveState.Connected("amber-otter-seven", null, pausedStatus, null, "Home network"), controls(from = pausedStatus), {}) { Scene() }
        }
        "live_siren" -> Shell(CameraTab.LIVE) {
            LiveScreen(LiveState.Connected("amber-otter-seven", null, status, null, "Remote"), controls(siren = true), {}) { Scene() }
        }
        "live_waiting" -> Shell(CameraTab.LIVE) {
            LiveScreen(LiveState.Connected("amber-otter-seven", null, status, null), null, {})
        }
        "live_finding" -> Shell(CameraTab.LIVE, armed = false) { LiveScreen(LiveState.Locating("Front door"), null, {}) }
        "live_failed" -> Shell(CameraTab.LIVE) {
            LiveScreen(
                LiveState.Failed(
                    "Front door is not answering on this network, and it has no remote address. To watch from " +
                        "anywhere else, connect once at home and set its Tailscale name under Settings → Remote address."
                ),
                null, {},
            )
        }
        "clips" -> Shell(CameraTab.CLIPS) { Clips(null) }
        "player" -> Shell(CameraTab.CLIPS) {
            Clips(java.io.File(androidx.compose.ui.platform.LocalContext.current.cacheDir, "sample.mp4"))
        }
        "clips_empty" -> Shell(CameraTab.CLIPS) { ClipsScreen(emptyList(), null, null, null, null, {}, {}, {}, {}, true) }
        "log" -> AuditScreen(events, AuditChain.Verification(true, 0), onBack = {})
        "log_broken" -> AuditScreen(events, AuditChain.Verification(false, 37), onBack = {})
        "settings" -> Shell(CameraTab.SETTINGS) { Settings(settings, "100.64.0.9") }
        "settings_disarmed" -> Shell(CameraTab.SETTINGS, armed = false) {
            Settings(settings.toBuilder().setArmed(false).setSoundDetection(SoundDetection.getDefaultInstance()).build(), "")
        }
        "devices" -> RemoteRosterScreen(viewers, "KQSP-NHPF-2NNZ", {}, {})
        "network" -> NetworkActivityScreen(viewerEgress(true, "https://ntfy.sh"), {})
        "pair_confirm" -> PairConfirmScreen(PairState.Confirm("amber-otter-seven"), {}, {}, {})
        "pair_failed" -> PairConfirmScreen(PairState.Failed("That code has expired or was already used. Start pairing again on the camera."), {}, {}, {})
        "cam_home" -> CameraHome(null)
        "cam_home_live" -> CameraHome("Samsung SM-S938U")
        "cam_pair" -> PairingScreen(null, null, {}, {}, {})
        "cam_pair_phrase" -> PairingScreen(null, "amber-otter-seven", {}, {}, {})
        "cam_roster" -> RosterScreen(
            viewers.map { ViewerEntity(it.fingerprint, ByteArray(0), it.label, it.pairedAtMs, it.lastSeenMs) },
            { 12 }, {}, {}, {},
        )
        "cam_roster_empty" -> RosterScreen(emptyList(), { 0 }, {}, {}, {})
    }
}

@Composable
private fun Settings(snapshot: SettingsSnapshot, remote: String) {
    CameraSettingsScreen(
        settings = snapshot, onArmed = {}, onAlias = {}, onMicrophone = {}, onRecordingMode = {},
        projectedDays = 14.0, onDetectionMode = { _, _, _, _ -> }, autoSpotlightSupported = true,
        autoSpotlight = false, onAutoSpotlight = {}, reminderEnabled = false, onDetectionReminder = {},
        soundDetectionEnabled = snapshot.soundDetection.enabled, onSoundDetection = {},
        soundClasses = com.homecam.core.sound.SoundClasses.pushSelection(snapshot.soundRecognition.classesList).toSet(), onSoundClass = { _, _ -> },
        alertsEnabled = true, onAlerts = {}, remoteAddress = remote, onRemoteAddress = {}, onDevices = {},
        protection = com.homecam.core.health.HealthConfig(),
        verificationPhrase = "amber-otter-seven", fingerprint = "Y1TM-4JVY-9K8F",
    )
}

@Composable
private fun CameraHome(watcher: String?) {
    CameraHomeScreen(
        alias = "Front door", accessCount = 2, liveViewerLabel = watcher, serving = true, armed = true,
        hardwareBacked = true, recordingMode = RecordingMode.CONTINUOUS, projectedDays = 14.0,
        onArmedChange = {}, onRecordingMode = {}, protection = com.homecam.core.health.HealthConfig(), onProtection = {},
        onManage = {}, onPair = {}, onLog = {}, onNetwork = {},
        lenses = sampleLenses, lens = "back-wide",
        canDrawOverlays = watcher == null,
    )
}

@Composable
private fun Clips(file: java.io.File?) {
    var playingId by remember { mutableStateOf(if (file != null) "run-a-0" else null) }
    ClipsScreen(
        clips = clips, playing = file.takeIf { playingId != null }, playingId = playingId, downloadingId = null,
        projectedDays = 14.0, onPlay = { if (file != null) playingId = it.id }, onPin = {}, onDelete = {}, onExport = {},
        showBoxes = true, loadThumbnail = { thumbnail(it) },
    )
}
