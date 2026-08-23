package com.homecam.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class Egress(
    val destination: String,
    val when_: String,
    val content: String,
    val active: Boolean,
)

/**
 * §2.9's egress inventory, in the app rather than only in a document. Three outbound
 * connections exist and this screen is the complete list — if something is not here, the
 * app does not make it.
 */
@Composable
fun NetworkActivityScreen(entries: List<Egress>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Network activity", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Everything this app sends, and when. There is no analytics, no crash " +
                "reporting, no remote config and no ad SDK — not disabled, absent.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            entries.forEach { entry ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (entry.active) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(entry.destination, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (entry.active) entry.when_ else "${entry.when_} — off",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(entry.content, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

fun cameraEgress(alertsRegistered: Boolean, alertEndpoint: String): List<Egress> = listOf(
    Egress(
        destination = "Paired viewers",
        when_ = "While a session is open",
        content = "Video, audio and control RPC, end to end under DTLS-SRTP. " +
            "No relay: the two phones address each other directly.",
        active = true,
    ),
    Egress(
        destination = alertEndpoint.ifBlank { "Push endpoint" },
        when_ = "On a detection or when a session starts",
        content = "Ciphertext only, to a random topic. The provider cannot read the " +
            "label, the camera name or the thumbnail.",
        active = alertsRegistered,
    ),
    Egress(
        destination = "Tailscale",
        when_ = "Only when you watch from outside the house",
        content = "Transport for the same encrypted session. Configured outside this app.",
        active = false,
    ),
)

fun viewerEgress(alertsEnabled: Boolean, alertEndpoint: String): List<Egress> = listOf(
    Egress(
        destination = "Your cameras",
        when_ = "While you are watching",
        content = "The pinned TLS signaling connection and the DTLS-SRTP media path.",
        active = true,
    ),
    Egress(
        destination = alertEndpoint.ifBlank { "Push endpoint" },
        when_ = "Continuously, while alerts are on",
        content = "An open connection waiting for ciphertext. Decryption happens on this " +
            "phone with a key that never leaves it.",
        active = alertsEnabled,
    ),
    Egress(
        destination = "Tailscale",
        when_ = "Only when a camera is not on this network",
        content = "Transport for the same encrypted session. Configured outside this app.",
        active = false,
    ),
)
