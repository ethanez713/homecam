package com.homecam.app.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import com.homecam.proto.TimelineCount
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
    CameraEntity("Y1TM-4JVY-9K8F", ByteArray(0), "Living room", "192.168.0.18", 8443, "100.64.0.9", 0, NOW - 90 * MINUTE, System.currentTimeMillis() - 2 * MINUTE),
    CameraEntity("Q7RD-2MXA-HH3C", ByteArray(0), "Garage", "192.168.0.31", 8443, "", 0, NOW - 9000 * MINUTE, System.currentTimeMillis() - 3 * 24 * 60 * MINUTE),
)

private val settings = SettingsSnapshot.newBuilder()
    .setArmed(true).setAlias("Living room").setMicrophone(true)
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

private val buckets = (0 until 40).map {
    TimelineCount.Bucket.newBuilder().setAtMs(System.currentTimeMillis() - (40 - it) * 60 * MINUTE)
        .setCount(listOf(0, 1, 0, 4, 2, 0, 0, 7, 3, 1)[it % 10]).build()
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

private fun thumbnail(seed: String): ByteArray {
    val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFF6E6558.toInt())
    val paint = Paint().apply { color = 0xFFD9CFBE.toInt() }
    canvas.drawRect(0f, 0f, 160f, 44f + (seed.hashCode() and 15), paint)
    paint.color = 0xFF3A342B.toInt()
    canvas.drawRect(20f + (seed.hashCode() and 31), 50f, 70f + (seed.hashCode() and 31), 100f, paint)
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
}

@Composable
private fun Room() {
    val bytes = remember { thumbnail("room") }
    val image = remember { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }
    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
}

private fun controls(siren: Boolean = false) = LiveControls(
    torchOn = false, micOn = false, zoom = 0.25f, onTorch = {}, onMic = {}, onZoom = {},
    sirenAvailable = true, sirenOn = siren,
)

@Composable
private fun Shell(tab: CameraTab, armed: Boolean = true, content: @Composable () -> Unit) {
    var current by remember { mutableStateOf(tab) }
    CameraShell("Living room", "Y1TM-4JVY-9K8F", armed, current, { current = it }, {}, content = content)
}

@Composable
private fun Screen(name: String) {
    when (name) {
        "role" -> RolePickerScreen {}
        "viewer_home" -> ViewerHomeScreen(cameras, {}, {}, {}, {}, {})
        "viewer_home_empty" -> ViewerHomeScreen(emptyList(), {}, {}, {}, {}, {})
        "live" -> Shell(CameraTab.LIVE) {
            LiveScreen(LiveState.Connected("amber-otter-seven", null, status, null, "Home network"), controls(), {}) { Room() }
        }
        "live_siren" -> Shell(CameraTab.LIVE) {
            LiveScreen(LiveState.Connected("amber-otter-seven", null, status, null, "Remote"), controls(siren = true), {}) { Room() }
        }
        "live_waiting" -> Shell(CameraTab.LIVE) {
            LiveScreen(LiveState.Connected("amber-otter-seven", null, status, null), null, {})
        }
        "live_finding" -> Shell(CameraTab.LIVE, armed = false) { LiveScreen(LiveState.Locating("Living room"), null, {}) }
        "live_failed" -> Shell(CameraTab.LIVE) {
            LiveScreen(
                LiveState.Failed(
                    "Living room is not answering on this network, and it has no remote address. To watch from " +
                        "anywhere else, connect once at home and set its Tailscale name under Settings → Remote address."
                ),
                null, {},
            )
        }
        "clips" -> Shell(CameraTab.CLIPS) {
            ClipsScreen(clips, null, "run-a-1", 14.0, {}, {}, {}, true, {}, { thumbnail(it) }, buckets)
        }
        "player" -> ClipsScreen(
            clips, java.io.File(androidx.compose.ui.platform.LocalContext.current.cacheDir, "sample.mp4"),
            null, 14.0, {}, {}, {}, true, {}, playingId = "solo-1",
        )
        "clips_empty" -> Shell(CameraTab.CLIPS) { ClipsScreen(emptyList(), null, null, null, {}, {}, {}, true, {}) }
        "log" -> Shell(CameraTab.LOG) { AuditScreen(events, AuditChain.Verification(true, 0)) }
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
        soundClasses = snapshot.soundRecognition.classesList.toSet(), onSoundClass = { _, _ -> },
        alertsEnabled = true, onAlerts = {}, remoteAddress = remote, onRemoteAddress = {}, onDevices = {},
        verificationPhrase = "amber-otter-seven",
    )
}

@Composable
private fun CameraHome(watcher: String?) {
    CameraHomeScreen(
        alias = "Living room", accessCount = 2, liveViewerLabel = watcher, serving = true, armed = true,
        hardwareBacked = true, recordingMode = RecordingMode.CONTINUOUS, projectedDays = 14.0,
        onArmedChange = {}, onRecordingMode = {}, onManage = {}, onPair = {}, onLog = {}, onNetwork = {},
        canDrawOverlays = watcher == null,
    )
}
