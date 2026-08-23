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
import com.homecam.app.AppGraph
import com.homecam.app.camera.ui.AuditScreen
import com.homecam.app.camera.ui.CameraHomeScreen
import com.homecam.app.camera.ui.PairingScreen
import com.homecam.app.camera.ui.RosterScreen
import com.homecam.core.audit.AuditChain
import kotlinx.coroutines.launch

private enum class CameraScreen { HOME, ROSTER, PAIRING, LOG }

@Composable
fun CameraRoot() {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val node = remember { graph.cameraNode }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(CameraScreen.HOME) }

    val settings by graph.settings.settings.collectAsState()
    val roster by node.roster.collectAsState()
    val accessCount by node.accessCount.collectAsState()
    val pairing by node.pairing.collectAsState()

    when (screen) {
        CameraScreen.HOME -> CameraHomeScreen(
            alias = settings.alias,
            accessCount = accessCount,
            liveViewerLabel = null,
            armed = settings.armed,
            hardwareBacked = graph.identity.hardwareBacked,
            onArmedChange = { armed -> scope.launch { graph.settings.update { setArmed(armed) } } },
            onManage = { screen = CameraScreen.ROSTER },
            onPair = { screen = CameraScreen.PAIRING },
            onLog = { screen = CameraScreen.LOG },
        )

        CameraScreen.ROSTER -> RosterScreen(
            viewers = roster,
            sessionsIn30d = node::sessionsIn30d,
            onRevoke = { scope.launch { node.revoke(it) } },
            onRevokeAll = { scope.launch { node.revokeAll(); screen = CameraScreen.HOME } },
        )

        CameraScreen.PAIRING -> PairingScreen(
            session = pairing,
            verificationPhrase = null,
            onStart = { node.startPairing(com.homecam.app.net.Signaling.DEFAULT_PORT) },
            onCancel = { node.cancelPairing(); screen = CameraScreen.HOME },
            onDone = { screen = CameraScreen.HOME },
        )

        CameraScreen.LOG -> {
            val events by graph.audit.observeRecent().collectAsState(emptyList())
            val verification by produceState<AuditChain.Verification?>(null, events.size) {
                value = graph.audit.verify()
            }
            AuditScreen(events, verification)
        }
    }
}
