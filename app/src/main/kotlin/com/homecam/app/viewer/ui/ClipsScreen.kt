package com.homecam.app.viewer.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.formatBytes
import com.homecam.app.ui.formatDuration
import com.homecam.app.ui.formatStamp
import com.homecam.proto.Clip
import java.io.File

@Composable
fun ClipsScreen(
    clips: List<Clip>?,
    playing: File?,
    downloadingId: String?,
    projectedDays: Double?,
    onPlay: (Clip) -> Unit,
    onPin: (Clip) -> Unit,
    onDelete: (Clip) -> Unit,
    onClosePlayer: () -> Unit,
) {
    if (playing != null) {
        Player(playing, onClosePlayer)
        return
    }

    if (clips == null) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Clips", style = MaterialTheme.typography.headlineSmall)
        projectedDays?.let {
            // The number worth showing: the raw limits do not tell you how far back the
            // camera can actually remember at the rate it is currently recording (§5).
            Text(
                "≈ %.0f days at the current rate".format(it),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (clips.isEmpty()) {
            Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(clips, key = { it.id }) { clip ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(formatStamp(clip.startedAtMs), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${formatDuration(clip.durationMs.toLong())} · " +
                                    "${formatBytes(clip.sizeBytes)} · ${clip.width}×${clip.height}" +
                                    clip.labelsList.joinToString("") { label -> " · $label" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { onPin(clip) }) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = if (clip.pinned) "Unpin" else "Pin",
                                tint = if (clip.pinned) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (downloadingId == clip.id) {
                            CircularProgressIndicator(Modifier.height(24.dp))
                        } else {
                            TextButton(onClick = { onPlay(clip) }) { Text("Play") }
                        }
                        TextButton(onClick = { onDelete(clip) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Player(file: File, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    setVideoPath(file.absolutePath)
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setOnPreparedListener { start() }
                }
            },
        )
        TextButton(onClick = onClose, modifier = Modifier.padding(16.dp)) {
            Text("Close", color = Color.White)
        }
    }
}
