package com.homecam.app.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.formatAgo
import com.homecam.app.ui.formatStamp
import com.homecam.proto.Viewer

/**
 * §2.6, read over the wire: the same roster the camera shows, so the camera can be audited
 * from the phone in your pocket rather than only from the hallway. Revoke works from here
 * too, including revoking the device you are holding.
 */
@Composable
fun RemoteRosterScreen(
    viewers: List<Viewer>?,
    selfFingerprint: String,
    onRevoke: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (viewers == null) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
        return
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            "${viewers.size} ${if (viewers.size == 1) "device has" else "devices have"} access",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            items(viewers, key = { it.fingerprint }) { viewer ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            viewer.label + if (viewer.fingerprint == selfFingerprint) " (this phone)" else "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            viewer.fingerprint,
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                        )
                        Text(
                            "paired ${formatStamp(viewer.pairedAtMs)} · seen ${formatAgo(viewer.lastSeenMs)} · " +
                                "${viewer.sessions30D} sessions in 30d",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onRevoke(viewer.fingerprint) }) { Text("Revoke") }
                        }
                    }
                }
            }
        }

        TextButton(onClick = onBack) { Text("Back") }
    }
}
