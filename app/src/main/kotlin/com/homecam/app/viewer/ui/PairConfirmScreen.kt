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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

sealed interface PairState {
    data object Connecting : PairState
    data class Confirm(val phrase: String) : PairState
    data class Failed(val message: String) : PairState
}

@Composable
fun PairConfirmScreen(state: PairState, onAccept: () -> Unit, onReject: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            PairState.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Proving this phone to the camera…")
            }

            is PairState.Confirm -> {
                Text("Do these match?", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(20.dp))
                Text(
                    state.phrase,
                    style = MaterialTheme.typography.displaySmall
                        .copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "The camera phone is showing a phrase too. If they differ, something " +
                        "relayed the connection — say no and revoke it from the camera.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onAccept) { Text("They match") }
                TextButton(onClick = onReject) { Text("They don't match") }
            }

            is PairState.Failed -> {
                Text("Pairing failed", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(state.message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onRetry) { Text("Scan again") }
            }
        }
    }
}
