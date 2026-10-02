package com.homecam.app.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.app.R
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.kit.PrimaryButton
import com.homecam.app.ui.kit.SecondaryButton
import com.homecam.app.ui.theme.Ethan

sealed interface PairState {
    data object Connecting : PairState
    data class Confirm(val phrase: String) : PairState
    data class Failed(val message: String) : PairState
}

@Composable
fun PairConfirmScreen(state: PairState, onAccept: () -> Unit, onReject: () -> Unit, onRetry: () -> Unit) {
    when (state) {
        PairState.Connecting -> EmptyState("Proving this phone to the camera", busy = true)

        is PairState.Failed -> EmptyState(
            line = "Pairing failed",
            detail = state.message,
            icon = R.drawable.lucide_circle_alert,
        ) { PrimaryButton("Scan again", onRetry, icon = R.drawable.lucide_qr_code) }

        is PairState.Confirm -> PhraseCheck(
            phrase = state.phrase,
            explanation = "The camera phone is showing a phrase too. If they differ, something relayed " +
                "the connection: say no, and revoke it from the camera.",
        ) {
            SecondaryButton("They differ", onReject, Modifier.weight(1f))
            PrimaryButton("They match", onAccept, Modifier.weight(1f))
        }
    }
}

/** The step both phones show at the end of pairing, so the two read as one check. */
@Composable
fun PhraseCheck(phrase: String, explanation: String, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = Gutter, vertical = 24.dp), verticalArrangement = Arrangement.Center) {
        Text("Do these match?", style = Ethan.type.display, color = Ethan.colors.textHeading)
        Spacer(Modifier.height(14.dp))
        PaperCard(padding = 16.dp) {
            Text("VERIFICATION PHRASE", style = Ethan.type.micro, color = Ethan.colors.textFaint)
            Spacer(Modifier.height(4.dp))
            Text(phrase, style = Ethan.type.monoLarge, color = Ethan.colors.textHeading)
        }
        Spacer(Modifier.height(12.dp))
        Text(explanation, style = Ethan.type.body, color = Ethan.colors.textBody)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}
