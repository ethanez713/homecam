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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.homecam.app.detect.DetectionTrackReader
import com.homecam.proto.DetectionFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    showBoxes: Boolean,
    onClosePlayer: () -> Unit,
    loadThumbnail: (suspend (String) -> ByteArray?)? = null,
) {
    if (playing != null) {
        Player(playing, showBoxes, onClosePlayer)
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
                        loadThumbnail?.let {
                            ClipThumbnail(clip.id, it)
                            Spacer(Modifier.width(12.dp))
                        }
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
private fun ClipThumbnail(clipId: String, load: suspend (String) -> ByteArray?) {
    val image by produceState<ImageBitmap?>(null, clipId) {
        val bytes = withContext(Dispatchers.IO) { load(clipId) }
        value = bytes?.let {
            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
        }
    }
    Box(
        Modifier
            .size(width = 72.dp, height = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Player(file: File, showBoxes: Boolean, onClose: () -> Unit) {
    // Straight from the file: the clip carries its own boxes, so playback needs neither a
    // sidecar nor the camera to be reachable.
    val frames by produceState(emptyMap<Long, DetectionFrame>(), file) {
        value = withContext(Dispatchers.IO) { DetectionTrackReader.read(file) }
    }
    var view by remember { mutableStateOf<VideoView?>(null) }
    var position by remember { mutableLongStateOf(0L) }

    LaunchedEffect(view, showBoxes) {
        while (showBoxes && view != null) {
            position = view?.currentPosition?.toLong() ?: 0L
            delay(100)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    setVideoPath(file.absolutePath)
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setOnPreparedListener { start() }
                    view = this
                }
            },
        )

        if (showBoxes) {
            DetectionTrackReader.at(frames, position)?.let { BoxOverlay(it) }
        }

        TextButton(onClick = onClose, modifier = Modifier.padding(16.dp)) {
            Text("Close", color = Color.White)
        }
    }
}

@Composable
private fun BoxOverlay(frame: DetectionFrame) {
    val labelStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        frame.boxesList.forEach { box ->
            val left = box.left * size.width
            val top = box.top * size.height
            drawRect(
                color = Color(0xFF7FD1B9),
                topLeft = Offset(left, top),
                size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                style = Stroke(width = 3f),
            )
            drawText(
                measurer,
                "${box.label} ${(box.confidence * 100).toInt()}%",
                topLeft = Offset(left, (top - 36f).coerceAtLeast(0f)),
                style = labelStyle.copy(color = Color(0xFF7FD1B9)),
            )
        }
    }
}
