package com.homecam.app.camera.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.homecam.app.camera.CameraNode
import com.homecam.app.data.ViewerEntity
import com.homecam.app.ui.formatAgo
import com.homecam.app.ui.formatStamp

/**
 * The roster from §2.6. Everything here is one screen deep from the home screen because
 * a control nobody can find is not a control.
 */
@Composable
fun RosterScreen(
    viewers: List<ViewerEntity>,
    sessionsIn30d: suspend (String) -> Int,
    onRevoke: (String) -> Unit,
    onRevokeAll: () -> Unit,
) {
    var confirmRevokeAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "${viewers.size} ${if (viewers.size == 1) "device has" else "devices have"} access",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))

        if (viewers.isEmpty()) {
            Text(
                "Nobody can see this camera. Start pairing to let a device in.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(viewers, key = { it.fingerprint }) { viewer ->
                ViewerCard(viewer, sessionsIn30d) { onRevoke(viewer.fingerprint) }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { confirmRevokeAll = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Revoke all")
        }
    }

    if (confirmRevokeAll) {
        AlertDialog(
            onDismissRequest = { confirmRevokeAll = false },
            title = { Text("Revoke every device?") },
            text = {
                Text(
                    "This camera goes back to unpaired. Any live session ends immediately " +
                        "and every device has to scan a new code."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRevokeAll = false; onRevokeAll() }) { Text("Revoke all") }
            },
            dismissButton = { TextButton(onClick = { confirmRevokeAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ViewerCard(
    viewer: ViewerEntity,
    sessionsIn30d: suspend (String) -> Int,
    onRevoke: () -> Unit,
) {
    val sessions by produceState(initialValue = -1, viewer.fingerprint) {
        value = sessionsIn30d(viewer.fingerprint)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(viewer.label, style = MaterialTheme.typography.titleMedium)
            Text(
                viewer.fingerprint,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(8.dp))
            Field("Paired", formatStamp(viewer.pairedAtMs))
            Field("Last seen", formatAgo(viewer.lastSeenMs))
            Field("Sessions (30d)", if (sessions < 0) "…" else sessions.toString())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRevoke) { Text("Revoke") }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
