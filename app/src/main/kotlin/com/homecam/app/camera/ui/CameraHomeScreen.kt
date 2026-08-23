package com.homecam.app.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * §2.6 puts the access count above the fold, and §2.7 puts a live indicator on the camera
 * device itself. Both are here for the same reason: the person walking past the phone in
 * the hallway is the one who most needs to know somebody is watching.
 */
@Composable
fun CameraHomeScreen(
    alias: String,
    accessCount: Int,
    liveViewerLabel: String?,
    armed: Boolean,
    hardwareBacked: Boolean,
    onArmedChange: (Boolean) -> Unit,
    onManage: () -> Unit,
    onPair: () -> Unit,
    onLog: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        if (liveViewerLabel != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "● LIVE — $liveViewerLabel",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(alias, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$accessCount ${if (accessCount == 1) "device has" else "devices have"} access",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onManage) { Text("Manage") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Armed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (armed) "Recording on detection" else "Watching only, not recording",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = armed, onCheckedChange = onArmedChange)
            }
        }

        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                Text("Pair a device")
            }
            OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) {
                Text("Access log")
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            if (hardwareBacked) "Identity key is held in this phone's secure hardware"
            else "Identity key is held in software — this phone has no hardware keystore",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
