package com.homecam.app.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import com.homecam.app.AppGraph
import com.homecam.app.camera.ui.AuditScreen
import com.homecam.app.camera.ui.CameraHomeScreen
import com.homecam.app.camera.ui.PairingScreen
import com.homecam.app.camera.ui.RosterScreen
import com.homecam.app.ui.NetworkActivityScreen
import com.homecam.app.ui.PermissionGate
import com.homecam.app.data.toRetentionLimits
import com.homecam.app.ui.cameraEgress
import com.homecam.core.audit.AuditChain
import com.homecam.core.retention.ClipRef
import com.homecam.core.retention.RetentionPlanner
import kotlinx.coroutines.launch

private enum class CameraScreen { HOME, ROSTER, PAIRING, LOG, NETWORK }

@Composable
fun CameraRoot() {
    PermissionGate(
        permissions = cameraRolePermissions(),
        rationale = "This phone is the camera. It needs the camera to see, the microphone " +
            "for two-way audio, and notifications so it can tell you when someone connects.",
    ) { CameraContent() }
}

private fun cameraRolePermissions(): List<String> = buildList {
    add(android.Manifest.permission.CAMERA)
    add(android.Manifest.permission.RECORD_AUDIO)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        add(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun CameraContent() {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val node = remember { graph.cameraNode }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(CameraScreen.HOME) }

    val settings by graph.settings.settings.collectAsState()
    val roster by node.roster.collectAsState()
    val accessCount by node.accessCount.collectAsState()
    val pairing by node.pairing.collectAsState()
    val pairingPhrase by node.pairingPhrase.collectAsState()
    val serviceRunning by CameraService.isRunning.collectAsState()
    val liveViewer by CameraService.liveViewerLabel.collectAsState()
    val projectedDays by produceState<Double?>(null, settings.recordingMode, settings.retention) {
        value = runCatching {
            val refs = graph.database.clips().all()
                .map { ClipRef(it.id, it.startedAtMs, it.sizeBytes, it.pinned) }
            RetentionPlanner.projectedRetentionDays(
                refs, settings.retention.toRetentionLimits(), System.currentTimeMillis(),
            )
        }.getOrNull()
    }

    LaunchedEffect(Unit) { CameraService.start(context) }

    when (screen) {
        CameraScreen.HOME -> CameraHomeScreen(
            alias = settings.alias,
            accessCount = accessCount,
            liveViewerLabel = liveViewer,
            serving = serviceRunning,
            armed = settings.armed,
            hardwareBacked = graph.identity.hardwareBacked,
            recordingMode = settings.recordingMode,
            projectedDays = projectedDays,
            onArmedChange = { armed -> scope.launch { graph.settings.update { setArmed(armed) } } },
            onRecordingMode = { mode -> scope.launch { graph.settings.update { setRecordingMode(mode) } } },
            onManage = { screen = CameraScreen.ROSTER },
            onPair = { screen = CameraScreen.PAIRING },
            onLog = { screen = CameraScreen.LOG },
            onNetwork = { screen = CameraScreen.NETWORK },
        )

        CameraScreen.ROSTER -> RosterScreen(
            viewers = roster,
            sessionsIn30d = node::sessionsIn30d,
            onRevoke = { scope.launch { node.revoke(it) } },
            onRevokeAll = { scope.launch { node.revokeAll(); screen = CameraScreen.HOME } },
        )

        CameraScreen.PAIRING -> PairingScreen(
            session = pairing,
            verificationPhrase = pairingPhrase,
            onStart = { node.startPairing(com.homecam.app.net.Signaling.DEFAULT_PORT) },
            onCancel = { node.cancelPairing(); screen = CameraScreen.HOME },
            onDone = { node.acknowledgePhrase(); screen = CameraScreen.HOME },
        )

        CameraScreen.NETWORK -> {
            val alertable by produceState(0, roster.size) {
                value = graph.database.viewers().alertable().size
            }
            NetworkActivityScreen(
                entries = cameraEgress(
                    alertsRegistered = alertable > 0,
                    alertEndpoint = roster.firstOrNull { it.alertsEnabled }?.alertEndpoint.orEmpty(),
                ),
                onBack = { screen = CameraScreen.HOME },
            )
        }

        CameraScreen.LOG -> {
            val events by graph.audit.observeRecent().collectAsState(emptyList())
            val verification by produceState<AuditChain.Verification?>(null, events.size) {
                value = graph.audit.verify()
            }
            AuditScreen(events, verification)
        }
    }
}
