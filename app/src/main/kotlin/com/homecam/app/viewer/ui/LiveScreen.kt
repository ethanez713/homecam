package com.homecam.app.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.formatBytes
import com.homecam.app.ui.formatDuration
import com.homecam.proto.Presence
import com.homecam.proto.RuntimeStatus
import org.webrtc.VideoTrack

sealed interface LiveState {
    data class Locating(val alias: String) : LiveState
    data class Connecting(val host: String, val port: Int) : LiveState
    data class Connected(
        val phrase: String,
        val presence: Presence?,
        val status: RuntimeStatus?,
        val video: VideoTrack?,
    ) : LiveState
    data class Failed(val message: String) : LiveState
}

data class LiveControls(
    val torchOn: Boolean,
    val micOn: Boolean,
    val zoom: Float,
    val onTorch: (Boolean) -> Unit,
    val onMic: (Boolean) -> Unit,
    val onZoom: (Float) -> Unit,
    // Hidden when the camera's capabilities say it has no flash / no speaker (Phase 2).
    val torchAvailable: Boolean = true,
    val pushToTalkAvailable: Boolean = true,
    // The siren is a deliberate action, kept apart from the ordinary controls (Phase 8).
    val sirenAvailable: Boolean = false,
    val sirenOn: Boolean = false,
    val onSiren: (Boolean) -> Unit = {},
)

@Composable
fun LiveScreen(
    state: LiveState,
    controls: LiveControls?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state is LiveState.Connected && state.video != null) {
        LiveVideo(state, controls, onBack)
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            is LiveState.Locating -> Waiting("Finding ${state.alias} on this network…")
            is LiveState.Connecting -> Waiting("Connecting to ${state.host}:${state.port}")

            is LiveState.Connected -> {
                Waiting(
                    when (state.presence?.state) {
                        Presence.State.BUSY -> "Camera is busy with another device"
                        Presence.State.DISABLED -> "Camera is disabled"
                        else -> "Waiting for the first frame…"
                    }
                )
                Spacer(Modifier.height(24.dp))
                Verification(state.phrase)
                state.status?.let { Spacer(Modifier.height(16.dp)); StatusReadout(it) }
            }

            is LiveState.Failed -> {
                Text("Not connected", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(state.message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onRetry) { Text("Try again") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun LiveVideo(state: LiveState.Connected, controls: LiveControls?, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(state.video, Modifier.fillMaxSize())

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            controls?.let {
                Slider(
                    value = it.zoom,
                    onValueChange = it.onZoom,
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (it.torchAvailable) {
                        IconButton(onClick = { it.onTorch(!it.torchOn) }) {
                            Icon(
                                if (it.torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                contentDescription = "Torch",
                                tint = Color.White,
                            )
                        }
                    }
                    if (it.pushToTalkAvailable) {
                        IconButton(onClick = { it.onMic(!it.micOn) }) {
                            Icon(
                                if (it.micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                                contentDescription = "Talk",
                                tint = Color.White,
                            )
                        }
                    }
                }
                if (it.sirenAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { it.onSiren(!it.sirenOn) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text(if (it.sirenOn) "Stop siren" else "Sound siren") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun Waiting(message: String) {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text(message, textAlign = TextAlign.Center)
}

@Composable
private fun Verification(phrase: String) {
    Text("Verification", style = MaterialTheme.typography.bodySmall)
    Text(
        phrase,
        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun StatusReadout(status: RuntimeStatus) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "%.0f%% battery%s · %.1f°C".format(
                status.batteryPercent,
                if (status.charging) " (charging)" else "",
                status.batteryTempC,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${formatBytes(status.freeBytes)} free · ${formatBytes(status.clipBytes)} of clips",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "up ${formatDuration(status.uptimeMs)} · ${status.viewersWithAccess} with access · " +
                if (status.armed) "armed" else "not armed",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
