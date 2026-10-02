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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.homecam.app.R
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.PrimaryButton
import com.homecam.app.ui.kit.SecondaryButton
import com.homecam.app.ui.kit.TopBar
import com.homecam.app.ui.theme.Ethan
import com.homecam.app.ui.theme.EthanRadius
import com.homecam.app.viewer.ui.PhraseCheck
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
    Column(Modifier.fillMaxSize()) {
        TopBar("Pair a device", onBack = onCancel)
        when {
            verificationPhrase != null -> PhraseCheck(
                phrase = verificationPhrase,
                explanation = "The other phone is showing a phrase too. If they differ, something is relaying " +
                    "the connection: revoke the device and pair again.",
            ) { PrimaryButton("They match", onDone, Modifier.weight(1f)) }

            session == null -> EmptyState(
                line = "Let another phone watch this camera",
                detail = "A code appears for two minutes and works once. Scan it from the phone you want " +
                    "to watch with, then check both phones show the same phrase.",
                icon = R.drawable.lucide_qr_code,
            ) { PrimaryButton("Show pairing code", onStart) }

            else -> CountdownStep(session, onCancel)
        }
    }
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

    Column(
        Modifier.fillMaxSize().padding(horizontal = Gutter, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A code is scanned as dark on light, whatever the theme around it.
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Pairing code",
            modifier = Modifier.fillMaxWidth(0.82f).aspectRatio(1f).clip(RoundedCornerShape(EthanRadius.card)),
        )
        Spacer(Modifier.height(16.dp))
        Text("Scan this from the other phone", style = Ethan.type.title2, color = Ethan.colors.textHeading)
        Text("${remaining / 1000} s left · works once", style = Ethan.type.mono, color = Ethan.colors.textMuted)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { (remaining.toFloat() / CameraPairingSession.DEFAULT_TTL_MS).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Ethan.colors.accent,
            trackColor = Ethan.colors.borderDefault,
        )
        Spacer(Modifier.height(16.dp))
        SecondaryButton("Cancel", onCancel)
    }
}
