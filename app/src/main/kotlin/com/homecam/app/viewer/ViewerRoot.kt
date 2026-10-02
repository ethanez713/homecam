package com.homecam.app.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.homecam.app.AppGraph
import com.homecam.app.camera.ui.AuditScreen
import androidx.compose.runtime.LaunchedEffect
import com.homecam.app.data.CameraEntity
import com.homecam.app.net.SignalingRefused
import com.homecam.app.viewer.ui.PairConfirmScreen
import com.homecam.app.viewer.ui.PairState
import com.homecam.app.viewer.ui.ScanScreen
import com.homecam.app.viewer.ui.ViewerHomeScreen
import com.homecam.app.ui.NetworkActivityScreen
import com.homecam.app.ui.viewerEgress
import com.homecam.core.audit.AuditChain
import com.homecam.proto.PairingInvite
import kotlinx.coroutines.launch

private sealed interface ViewerScreen {
    data object Home : ViewerScreen
    data object Scan : ViewerScreen
    data class Pairing(val invite: PairingInvite) : ViewerScreen
    data class Live(val camera: CameraEntity) : ViewerScreen
    data object Log : ViewerScreen
    data object Network : ViewerScreen
}

@Composable
fun ViewerRoot(onSwitchRole: () -> Unit = {}) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val node = remember { graph.viewerNode }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<ViewerScreen>(ViewerScreen.Home) }
    val cameras by node.paired.collectAsState()

    BackHandler(enabled = screen != ViewerScreen.Home && screen !is ViewerScreen.Live) { screen = ViewerScreen.Home }

    when (val current = screen) {
        ViewerScreen.Home -> ViewerHomeScreen(
            cameras = cameras,
            onAdd = { screen = ViewerScreen.Scan },
            onOpen = { screen = ViewerScreen.Live(it) },
            onForget = { scope.launch { node.forget(it) } },
            onLog = { screen = ViewerScreen.Log },
            onNetwork = { screen = ViewerScreen.Network },
            onSwitchRole = onSwitchRole,
        )

        ViewerScreen.Scan -> ScanScreen(onBack = { screen = ViewerScreen.Home }) { invite -> screen = ViewerScreen.Pairing(invite) }

        is ViewerScreen.Pairing -> {
            var state by remember(current.invite) {
                mutableStateOf<PairState>(PairState.Connecting)
            }
            LaunchedEffect(current.invite) {
                state = runCatching { node.pair(current.invite) }.fold(
                    onSuccess = { PairState.Confirm(it) },
                    onFailure = { PairState.Failed(describeFailure(it)) },
                )
            }
            PairConfirmScreen(
                state = state,
                onAccept = { screen = ViewerScreen.Home },
                onReject = {
                    scope.launch {
                        node.forget(
                            com.homecam.core.crypto.IdentityKey(
                                current.invite.cameraPublicKey.toByteArray()
                            ).shortFingerprint
                        )
                        screen = ViewerScreen.Home
                    }
                },
                onRetry = { screen = ViewerScreen.Scan },
            )
        }

        is ViewerScreen.Live -> CameraScreen(
            node = node,
            camera = current.camera,
            onExit = { screen = ViewerScreen.Home },
        )

        ViewerScreen.Network -> NetworkActivityScreen(
            entries = viewerEgress(
                alertsEnabled = com.homecam.app.alerts.AlertPreferences.enabled(context),
                alertEndpoint = com.homecam.app.alerts.AlertSubscriber.endpointOf(context),
            ),
            onBack = { screen = ViewerScreen.Home },
        )

        ViewerScreen.Log -> {
            val events by graph.audit.observeRecent().collectAsState(emptyList())
            val verification by produceState<AuditChain.Verification?>(null, events.size) {
                value = graph.audit.verify()
            }
            AuditScreen(events, verification, onBack = { screen = ViewerScreen.Home })
        }
    }
}

internal fun describeBye(reason: com.homecam.proto.Bye.Reason, detail: String): String = when (reason) {
    com.homecam.proto.Bye.Reason.FINGERPRINT_MISMATCH ->
        "The media stream did not match the camera that was authenticated. The session was " +
            "torn down and both devices logged it."
    com.homecam.proto.Bye.Reason.SESSION_REPLACED -> "Another device took over the camera."
    com.homecam.proto.Bye.Reason.REVOKED -> "This device's access was revoked."
    com.homecam.proto.Bye.Reason.CAMERA_BUSY -> "The camera is busy."
    com.homecam.proto.Bye.Reason.NO_FRAME -> "The camera connected but never sent a frame."
    com.homecam.proto.Bye.Reason.HANGUP -> "The session ended."
    com.homecam.proto.Bye.Reason.CAMERA_DISABLED -> "The camera stopped (${detail.ifBlank { "no reason given" }})."
    else -> detail.ifBlank { reason.name }
}

internal fun describeFailure(error: Throwable): String = when {
    error is SignalingRefused -> when (error.reason) {
        com.homecam.proto.Bye.Reason.PAIRING_EXPIRED ->
            "That code has expired or was already used. Start pairing again on the camera."
        com.homecam.proto.Bye.Reason.ACCESS_DENIED ->
            "The camera answered with a different identity than the code claimed."
        com.homecam.proto.Bye.Reason.INCOMPATIBLE ->
            "The two apps are running different protocol versions."
        else -> "The camera refused the connection (${error.reason.name})."
    }
    else -> error.message ?: "Could not reach the camera."
}
