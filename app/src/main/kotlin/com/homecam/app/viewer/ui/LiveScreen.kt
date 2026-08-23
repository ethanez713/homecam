package com.homecam.app.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.homecam.proto.Presence

sealed interface LiveState {
    data class Locating(val alias: String) : LiveState
    data class Connecting(val host: String, val port: Int) : LiveState
    data class Connected(val phrase: String, val presence: Presence?) : LiveState
    data class Failed(val message: String) : LiveState
}

@Composable
fun LiveScreen(state: LiveState, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            is LiveState.Locating -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Finding ${state.alias} on this network…")
            }

            is LiveState.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Connecting to ${state.host}:${state.port}")
            }

            is LiveState.Connected -> {
                Text(
                    when (state.presence?.state) {
                        Presence.State.AVAILABLE -> "Connected"
                        Presence.State.BUSY -> "Camera is busy with another device"
                        Presence.State.DISABLED -> "Camera is disabled"
                        else -> "Connected"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                state.presence?.cameraAlias?.takeIf { it.isNotBlank() }?.let { Text(it) }
                Spacer(Modifier.height(16.dp))
                Text("Verification", style = MaterialTheme.typography.bodySmall)
                Text(
                    state.phrase,
                    style = MaterialTheme.typography.titleLarge
                        .copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "Live video arrives at M4; the authenticated channel it rides on is here.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
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
