package com.homecam.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.kit.Badge
import com.homecam.app.ui.kit.Eyebrow
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.Tone
import com.homecam.app.ui.kit.TopBar
import com.homecam.app.ui.theme.Ethan

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
    Column(Modifier.fillMaxSize()) {
        TopBar("Network activity", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Gutter)) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Everything this app sends, and when. There is no analytics, no crash reporting, " +
                    "no remote config and no ad SDK. They are not switched off; they are absent.",
                style = Ethan.type.body,
                color = Ethan.colors.textBody,
            )
            Eyebrow("Outbound connections", trailing = "${entries.count { it.active }} of ${entries.size} on")
            PaperCard(padding = 0.dp) {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) Rule()
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.destination,
                                style = Ethan.type.bodyStrong,
                                color = Ethan.colors.textHeading,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Badge(if (entry.active) "On" else "Off", if (entry.active) Tone.SUCCESS else Tone.NEUTRAL, dot = true)
                        }
                        Text(entry.when_, style = Ethan.type.bodySmall, color = Ethan.colors.textMuted)
                        Text(entry.content, style = Ethan.type.bodySmall, color = Ethan.colors.textFaint)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
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
