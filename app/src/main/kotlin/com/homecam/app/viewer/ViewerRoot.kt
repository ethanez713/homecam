package com.homecam.app.viewer

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.homecam.app.data.CameraEntity
import com.homecam.app.net.SignalingClient
import com.homecam.app.net.SignalingRefused
import com.homecam.app.viewer.ui.LiveScreen
import com.homecam.app.viewer.ui.LiveState
import com.homecam.app.viewer.ui.PairConfirmScreen
import com.homecam.app.viewer.ui.PairState
import com.homecam.app.viewer.ui.ScanScreen
import com.homecam.app.viewer.ui.ViewerHomeScreen
import com.homecam.core.audit.AuditChain
import com.homecam.proto.PairingInvite
import kotlinx.coroutines.launch

private sealed interface ViewerScreen {
    data object Home : ViewerScreen
    data object Scan : ViewerScreen
    data class Pairing(val invite: PairingInvite) : ViewerScreen
    data class Live(val camera: CameraEntity, val attempt: Int = 0) : ViewerScreen
    data object Log : ViewerScreen
}

@Composable
fun ViewerRoot() {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val node = remember { graph.viewerNode }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<ViewerScreen>(ViewerScreen.Home) }
    val cameras by node.paired.collectAsState()

    when (val current = screen) {
        ViewerScreen.Home -> ViewerHomeScreen(
            cameras = cameras,
            onAdd = { screen = ViewerScreen.Scan },
            onOpen = { screen = ViewerScreen.Live(it) },
            onForget = { scope.launch { node.forget(it) } },
            onLog = { screen = ViewerScreen.Log },
        )

        ViewerScreen.Scan -> ScanScreen { invite -> screen = ViewerScreen.Pairing(invite) }

        is ViewerScreen.Pairing -> {
            var state by remember(current.invite) {
                mutableStateOf<PairState>(PairState.Connecting)
            }
            LaunchedEffect(current.invite) {
                state = runCatching { node.pair(current.invite) }.fold(
                    onSuccess = { PairState.Confirm(it) },
                    onFailure = { PairState.Failed(describe(it)) },
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

        is ViewerScreen.Live -> {
            var state by remember(current.camera.fingerprint, current.attempt) {
                mutableStateOf<LiveState>(LiveState.Locating(current.camera.alias))
            }
            var client by remember(current.camera.fingerprint, current.attempt) {
                mutableStateOf<SignalingClient?>(null)
            }
            DisposableEffect(client) { onDispose { client?.close() } }

            LaunchedEffect(current.camera.fingerprint, current.attempt) {
                state = runCatching {
                    val address = node.locate(current.camera)
                        ?: error("${current.camera.alias} is not answering on this network.")
                    state = LiveState.Connecting(address.host, address.port)
                    node.connect(current.camera)
                }.fold(
                    onSuccess = { connected ->
                        client = connected
                        LiveState.Connected(
                            connected.verificationPhrase.orEmpty(),
                            null,
                            runCatching { connected.link.runtimeStatus() }.getOrNull(),
                        )
                    },
                    onFailure = { LiveState.Failed(describe(it)) },
                )
            }

            LiveScreen(
                state = state,
                onBack = { screen = ViewerScreen.Home },
                onRetry = { screen = ViewerScreen.Live(current.camera, current.attempt + 1) },
            )
        }

        ViewerScreen.Log -> {
            val events by graph.audit.observeRecent().collectAsState(emptyList())
            val verification by produceState<AuditChain.Verification?>(null, events.size) {
                value = graph.audit.verify()
            }
            AuditScreen(events, verification)
        }
    }
}

private fun describe(error: Throwable): String = when {
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
