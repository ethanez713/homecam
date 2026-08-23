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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.homecam.app.data.CameraEntity
import com.homecam.app.ui.formatAgo

@Composable
fun ViewerHomeScreen(
    cameras: List<CameraEntity>,
    onAdd: () -> Unit,
    onOpen: (CameraEntity) -> Unit,
    onForget: (String) -> Unit,
    onLog: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Cameras", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (cameras.isEmpty()) {
            Text(
                "No cameras yet. Start pairing on the camera phone, then scan its code.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(cameras, key = { it.fingerprint }) { camera ->
                    Card(onClick = { onOpen(camera) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(camera.alias, style = MaterialTheme.typography.titleMedium)
                            Text(
                                camera.fingerprint,
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace),
                            )
                            Text(
                                "${camera.lastHost}:${camera.lastPort} · seen ${formatAgo(camera.lastSeenMs)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { onForget(camera.fingerprint) }) { Text("Forget") }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add a camera") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) { Text("Access log") }
    }
}
