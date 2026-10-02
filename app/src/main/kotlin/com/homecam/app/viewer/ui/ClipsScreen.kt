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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.homecam.core.media.ClipFilter
import com.homecam.core.media.ClipRun
import com.homecam.core.media.ClipRuns
import com.homecam.proto.Clip
import com.homecam.proto.TimelineCount
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    buckets: List<TimelineCount.Bucket> = emptyList(),
    pinnedNearCeiling: Boolean = false,
    onExport: (Clip) -> Unit = {},
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

    var activeLabel by remember(clips) { mutableStateOf<String?>(null) }
    var activeHourMs by remember(clips) { mutableStateOf<Long?>(null) }
    val labels = remember(clips) { clips.flatMap { it.labelsList }.distinct().sorted() }
    val shown = clips
        .let { if (activeLabel != null) ClipFilter.withLabel(it, activeLabel!!) else it }
        .let { list ->
            val hour = activeHourMs
            if (hour == null) list else list.filter { it.startedAtMs in hour until hour + HOUR_MS }
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
        if (pinnedNearCeiling) {
            Text(
                "Pinned clips are near the size limit — new recordings will be pushed out sooner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (clips.isEmpty()) {
            Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        if (buckets.isNotEmpty()) {
            DensityStrip(buckets, activeHourMs) { at -> activeHourMs = if (activeHourMs == at) null else at }
            Spacer(Modifier.height(8.dp))
        }
        if (labels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { label ->
                    FilterChip(
                        selected = activeLabel == label,
                        onClick = { activeLabel = if (activeLabel == label) null else label },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        var expanded by remember { mutableStateOf(setOf<Long>()) }
        val runs = remember(shown) { ClipRuns.group(shown) }
        val actions = ClipActions(onPlay, onPin, onExport, onDelete)

        LazyColumn {
            runs.groupBy { dayOf(it.startedAtMs) }.forEach { (day, runsThatDay) ->
                stickyHeader(key = "day-$day") { DayHeader(day) }
                runsThatDay.forEach { run ->
                    if (run.clips.size == 1) {
                        item(key = run.clips.single().id) {
                            ClipRow(run.clips.single(), downloadingId, loadThumbnail, actions)
                        }
                    } else {
                        val open = run.startedAtMs in expanded
                        item(key = "run-${run.startedAtMs}") {
                            RunRow(run, open, loadThumbnail) {
                                expanded = if (open) expanded - run.startedAtMs else expanded + run.startedAtMs
                            }
                        }
                        if (open) {
                            items(run.clips, key = { it.id }) { clip ->
                                ClipRow(clip, downloadingId, loadThumbnail, actions, indented = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

private class ClipActions(
    val play: (Clip) -> Unit,
    val pin: (Clip) -> Unit,
    val export: (Clip) -> Unit,
    val delete: (Clip) -> Unit,
)

private fun dayOf(atMs: Long): LocalDate = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate()

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val dayName = DateTimeFormatter.ofPattern("EEE d MMM")

private fun clockOf(atMs: Long): String = clock.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

@Composable
private fun DayHeader(day: LocalDate) {
    val today = LocalDate.now()
    Text(
        when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> dayName.format(day)
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 6.dp),
    )
}

/** A stretch of back-to-back clips, shown as one line until it is opened. */
@Composable
private fun RunRow(
    run: ClipRun,
    open: Boolean,
    loadThumbnail: (suspend (String) -> ByteArray?)?,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        loadThumbnail?.let {
            ClipThumbnail(run.clips.first().id, it)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${clockOf(run.startedAtMs)} – ${clockOf(run.endedAtMs)}" + run.labels.joinToString("") { " · $it" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatDuration(run.endedAtMs - run.startedAtMs)} · ${run.clips.size} clips · ${formatBytes(run.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (run.pinned) {
            Icon(Icons.Filled.PushPin, contentDescription = "Has pinned clips", tint = MaterialTheme.colorScheme.primary)
        }
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (open) "Collapse" else "Expand",
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** Tapping the row plays it; everything else is behind the pin and the menu. */
@Composable
private fun ClipRow(
    clip: Clip,
    downloadingId: String?,
    loadThumbnail: (suspend (String) -> ByteArray?)?,
    actions: ClipActions,
    indented: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { actions.play(clip) }
            .padding(start = if (indented) 24.dp else 0.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        loadThumbnail?.let {
            ClipThumbnail(clip.id, it)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                clockOf(clip.startedAtMs) + clip.labelsList.joinToString("") { " · $it" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatDuration(clip.durationMs.toLong())} · ${formatBytes(clip.sizeBytes)} · ${minOf(clip.width, clip.height)}p",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (downloadingId == clip.id) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        IconButton(onClick = { actions.pin(clip) }) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = if (clip.pinned) "Unpin" else "Pin",
                tint = if (clip.pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Play") }, onClick = { menuOpen = false; actions.play(clip) })
                DropdownMenuItem(text = { Text("Save to gallery") }, onClick = { menuOpen = false; actions.export(clip) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; actions.delete(clip) })
            }
        }
    }
}

/** Hourly activity density; a tapped bar filters the list to that hour. */
@Composable
private fun DensityStrip(
    buckets: List<TimelineCount.Bucket>,
    activeHourMs: Long?,
    onSelect: (Long) -> Unit,
) {
    val max = (buckets.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buckets.forEach { bucket ->
            val selected = bucket.atMs == activeHourMs
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(bucket.count.toFloat() / max)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    .clickable { onSelect(bucket.atMs) },
            )
        }
    }
}

private const val HOUR_MS = 3_600_000L

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
            .size(width = 64.dp, height = 40.dp)
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
