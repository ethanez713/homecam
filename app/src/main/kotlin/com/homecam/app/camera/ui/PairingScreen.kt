package com.homecam.app.camera.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.homecam.app.qr.QrCode
import com.homecam.core.pairing.CameraPairingSession
import kotlinx.coroutines.delay

/**
 * The pairing window (§2.3). The countdown is shown rather than implied: a code that
 * silently stopped working is the kind of thing people photograph and keep.
 */
@Composable
fun PairingScreen(
    session: CameraPairingSession?,
    verificationPhrase: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            verificationPhrase != null -> VerificationStep(verificationPhrase, onDone)
            session == null -> IdleStep(onStart)
            else -> CountdownStep(session, onCancel)
        }
    }
}

@Composable
private fun IdleStep(onStart: () -> Unit) {
    Text("Pair a device", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(
        "A code appears for two minutes and works once. Scan it from the phone you want " +
            "to watch with, then check the two devices show the same phrase.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onStart) { Text("Show pairing code") }
}

@Composable
private fun CountdownStep(session: CameraPairingSession, onCancel: () -> Unit) {
    var remaining by remember(session) { mutableLongStateOf(session.remainingMs(System.currentTimeMillis())) }
    LaunchedEffect(session) {
        while (remaining > 0) {
            delay(250)
            remaining = session.remainingMs(System.currentTimeMillis())
        }
        onCancel()
    }

    val payload = remember(session) { session.inviteText() }
    val bitmap = remember(payload) { QrCode.render(payload, sizePx = 720) }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Pairing code",
        modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(1f),
    )
    Spacer(Modifier.height(20.dp))
    Text("${remaining / 1000} s left", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { (remaining.toFloat() / CameraPairingSession.DEFAULT_TTL_MS).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun VerificationStep(phrase: String, onDone: () -> Unit) {
    Text("Do these match?", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(20.dp))
    Text(
        phrase,
        style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
    )
    Spacer(Modifier.height(20.dp))
    Text(
        "The other phone is showing a phrase too. If they differ, something is relaying " +
            "the connection — revoke the device and pair again.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onDone) { Text("They match") }
}
