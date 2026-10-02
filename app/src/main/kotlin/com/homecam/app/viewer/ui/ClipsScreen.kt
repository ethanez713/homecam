package com.homecam.app.viewer.ui

import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.homecam.app.R
import com.homecam.app.detect.DetectionTrackReader
import com.homecam.app.ui.formatBytes
import com.homecam.app.ui.formatDuration
import com.homecam.app.ui.kit.ConfirmSheet
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.EthanSlider
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.IconAction
import com.homecam.app.ui.kit.ListRow
import com.homecam.app.ui.kit.LucideIcon
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.Sheet
import com.homecam.app.ui.kit.Tag
import com.homecam.app.ui.theme.Ethan
import com.homecam.app.ui.theme.EthanRadius
import com.homecam.app.ui.theme.HomecamTheme
import androidx.compose.material3.Surface
import com.homecam.core.media.ClipFilter
import com.homecam.core.media.ClipRun
import com.homecam.core.media.ClipRuns
import com.homecam.proto.Clip
import com.homecam.proto.DetectionFrame
import com.homecam.proto.TimelineCount
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
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
    /** The clip [playing] belongs to, so the player can title itself and step to its neighbours. */
    playingId: String? = null,
) {
    if (playing != null) {
        // Oldest first: "next" is what happened next.
        val order = remember(clips) { clips.orEmpty().sortedBy { it.startedAtMs } }
        val at = order.indexOfFirst { it.id == playingId }
        // Footage is watched on a dark ground whatever the theme around it.
        HomecamTheme(dark = true) { Surface(Modifier.fillMaxSize(), color = Ethan.colors.bgSunken) { Player(
            file = playing,
            clip = order.getOrNull(at),
            showBoxes = showBoxes,
            loading = downloadingId != null,
            onPrevious = order.getOrNull(at - 1)?.takeIf { at > 0 }?.let { { onPlay(it) } },
            onNext = order.getOrNull(at + 1)?.takeIf { at >= 0 }?.let { { onPlay(it) } },
            onClose = onClosePlayer,
        ) } }
        return
    }

    if (clips == null) {
        EmptyState("Loading clips", busy = true)
        return
    }
    if (clips.isEmpty()) {
        EmptyState("Nothing recorded yet.", detail = "Clips appear here as the camera records them.", icon = R.drawable.lucide_film)
        return
    }

    var activeLabel by remember(clips) { mutableStateOf<String?>(null) }
    var pinnedOnly by remember(clips) { mutableStateOf(false) }
    var activeHourMs by remember(clips) { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<Clip?>(null) }
    var menuFor by remember { mutableStateOf<Clip?>(null) }
    val labels = remember(clips) { clips.flatMap { it.labelsList }.groupingBy { it }.eachCount().toSortedMap() }
    val pinnedCount = remember(clips) { clips.count { it.pinned } }
    val shown = clips
        .let { if (activeLabel != null) ClipFilter.withLabel(it, activeLabel!!) else it }
        .let { if (pinnedOnly) it.filter { clip -> clip.pinned } else it }
        .let { list ->
            val hour = activeHourMs
            if (hour == null) list else list.filter { it.startedAtMs in hour until hour + HOUR_MS }
        }

    var expanded by remember { mutableStateOf(setOf<Long>()) }
    val runs = remember(shown) { ClipRuns.group(shown) }

    Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${clips.size} clips · ${formatBytes(clips.sumOf { it.sizeBytes })}",
                style = Ethan.type.monoSmall, color = Ethan.colors.textMuted, modifier = Modifier.weight(1f),
            )
            projectedDays?.let {
                // The number worth showing: the raw limits do not tell you how far back the
                // camera can actually remember at the rate it is currently recording (§5).
                Text("≈ %.0f d kept".format(it), style = Ethan.type.monoSmall, color = Ethan.colors.textMuted)
            }
        }
        if (pinnedNearCeiling) {
            Text(
                "Pinned clips are near the size limit, so new recordings are pushed out sooner.",
                style = Ethan.type.bodySmall,
                color = Ethan.colors.textDanger,
            )
        }

        if (buckets.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            DensityStrip(buckets, activeHourMs) { at -> activeHourMs = if (activeHourMs == at) null else at }
        }
        if (labels.isNotEmpty() || pinnedCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { (label, count) ->
                    Tag("$label $count", selected = activeLabel == label, onClick = { activeLabel = if (activeLabel == label) null else label })
                }
                if (pinnedCount > 0) {
                    Tag("pinned $pinnedCount", selected = pinnedOnly, onClick = { pinnedOnly = !pinnedOnly })
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        if (runs.isEmpty()) {
            EmptyState("No clips match.", detail = "Clear the filter to see everything.", modifier = Modifier.height(200.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            runs.groupBy { dayOf(it.startedAtMs) }.forEach { (day, runsThatDay) ->
                stickyHeader(key = "day-$day") { DayHeader(day, runsThatDay.sumOf { it.clips.size }) }
                runsThatDay.forEach { run ->
                    if (run.clips.size == 1) {
                        item(key = run.clips.single().id) {
                            ClipRow(run.clips.single(), downloadingId, loadThumbnail, onPlay, onPin) { menuFor = it }
                            Rule()
                        }
                    } else {
                        val open = run.startedAtMs in expanded
                        item(key = "run-${run.startedAtMs}") {
                            RunRow(run, open, loadThumbnail) {
                                expanded = if (open) expanded - run.startedAtMs else expanded + run.startedAtMs
                            }
                            Rule()
                        }
                        if (open) {
                            items(run.clips, key = { it.id }) { clip ->
                                ClipRow(clip, downloadingId, loadThumbnail, onPlay, onPin, indented = true) { menuFor = it }
                                Rule(Modifier.padding(start = 20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { clip ->
        Sheet(onDismiss = { menuFor = null }) {
            Text(clipTitle(clip), style = Ethan.type.title2, color = Ethan.colors.textHeading)
            Text(clipMeta(clip), style = Ethan.type.monoSmall, color = Ethan.colors.textMuted)
            Spacer(Modifier.height(8.dp))
            ListRow("Play", leading = { LucideIcon(R.drawable.lucide_play, null) }, onClick = { menuFor = null; onPlay(clip) })
            Rule()
            ListRow(
                if (clip.pinned) "Unpin" else "Pin", meta = "A pinned clip is never deleted to make room",
                leading = { LucideIcon(R.drawable.lucide_pin, null) }, onClick = { menuFor = null; onPin(clip) },
            )
            Rule()
            ListRow("Save to gallery", leading = { LucideIcon(R.drawable.lucide_download, null) }, onClick = { menuFor = null; onExport(clip) })
            Rule()
            ListRow(
                "Delete", leading = { LucideIcon(R.drawable.lucide_trash_2, null, tint = Ethan.colors.textDanger) },
                onClick = { menuFor = null; deleting = clip },
            )
        }
    }
    deleting?.let { clip ->
        ConfirmSheet(
            title = "Delete this clip?",
            body = "${clipTitle(clip)} is removed from the camera. This cannot be undone.",
            confirm = "Delete",
            onConfirm = { onDelete(clip) },
            onDismiss = { deleting = null },
        )
    }
}

private fun dayOf(atMs: Long): LocalDate = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate()

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val clockSeconds = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dayName = DateTimeFormatter.ofPattern("EEEE d MMMM")

private fun clockOf(atMs: Long): String = clock.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

private fun clipTitle(clip: Clip): String =
    clockSeconds.format(Instant.ofEpochMilli(clip.startedAtMs).atZone(ZoneId.systemDefault())) +
        clip.labelsList.joinToString("") { " · $it" }

private fun clipMeta(clip: Clip): String =
    "${formatDuration(clip.durationMs.toLong())} · ${formatBytes(clip.sizeBytes)} · ${minOf(clip.width, clip.height)}p"

@Composable
private fun DayHeader(day: LocalDate, count: Int) {
    val today = LocalDate.now()
    Row(
        Modifier.fillMaxWidth().background(Ethan.colors.bgApp).padding(top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> dayName.format(day)
            }.uppercase(),
            style = Ethan.type.micro, color = Ethan.colors.textFaint, modifier = Modifier.weight(1f),
        )
        Text("$count CLIPS", style = Ethan.type.micro, color = Ethan.colors.textFaint)
    }
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
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        loadThumbnail?.let {
            ClipThumbnail((run.clips.firstOrNull { clip -> clip.labelsCount > 0 } ?: run.clips.first()).id, it)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${clockOf(run.startedAtMs)}–${clockOf(run.endedAtMs)}" + run.labels.joinToString("") { " · $it" },
                style = Ethan.type.mono.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W500),
                color = Ethan.colors.textHeading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatDuration(run.endedAtMs - run.startedAtMs)} · ${run.clips.size} clips · ${formatBytes(run.sizeBytes)}",
                style = Ethan.type.monoSmall,
                color = Ethan.colors.textMuted,
                maxLines = 1,
            )
        }
        if (run.pinned) {
            LucideIcon(R.drawable.lucide_pin, "Has pinned clips", size = 16.dp, tint = Ethan.colors.textAccent)
            Spacer(Modifier.width(10.dp))
        }
        Text(if (open) "▲" else "▼", style = Ethan.type.caption, color = Ethan.colors.textAccent, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

/** Tapping the row plays it; everything else is behind the pin and the menu. */
@Composable
private fun ClipRow(
    clip: Clip,
    downloadingId: String?,
    loadThumbnail: (suspend (String) -> ByteArray?)?,
    onPlay: (Clip) -> Unit,
    onPin: (Clip) -> Unit,
    indented: Boolean = false,
    onMenu: (Clip) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPlay(clip) }
            .padding(start = if (indented) 20.dp else 0.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        loadThumbnail?.let {
            ClipThumbnail(clip.id, it)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                clockOf(clip.startedAtMs) + clip.labelsList.joinToString("") { " · $it" },
                style = Ethan.type.mono.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W500),
                color = Ethan.colors.textHeading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(clipMeta(clip), style = Ethan.type.monoSmall, color = Ethan.colors.textMuted, maxLines = 1)
        }
        if (downloadingId == clip.id) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ethan.colors.accent)
            Spacer(Modifier.width(8.dp))
        }
        IconAction(
            R.drawable.lucide_pin,
            if (clip.pinned) "Unpin" else "Pin",
            tint = if (clip.pinned) Ethan.colors.textAccent else Ethan.colors.borderStrong,
        ) { onPin(clip) }
        IconAction(R.drawable.lucide_ellipsis_vertical, "More") { onMenu(clip) }
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
    Column {
        Row(
            Modifier.fillMaxWidth().height(36.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEach { bucket ->
                val selected = bucket.atMs == activeHourMs
                Box(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(bucket.atMs) }, contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((bucket.count.toFloat() / max).coerceAtLeast(0.08f))
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (selected) Ethan.colors.accent else Ethan.colors.borderStrong),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            Text(stripLabel(buckets.first().atMs), style = Ethan.type.micro, color = Ethan.colors.textFaint, modifier = Modifier.weight(1f))
            Text(
                activeHourMs?.let { "${stripLabel(it)} ONLY" } ?: "EVENTS BY HOUR",
                style = Ethan.type.micro,
                color = if (activeHourMs != null) Ethan.colors.textAccent else Ethan.colors.textFaint,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(stripLabel(buckets.last().atMs), style = Ethan.type.micro, color = Ethan.colors.textFaint)
        }
    }
}

private val stripStamp = DateTimeFormatter.ofPattern("d MMM HH:mm")

private fun stripLabel(atMs: Long): String =
    stripStamp.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())).uppercase()

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
            .clip(RoundedCornerShape(EthanRadius.row))
            .background(Ethan.colors.bgSunken),
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
private fun Player(
    file: File,
    clip: Clip?,
    showBoxes: Boolean,
    loading: Boolean,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // Straight from the file: the clip carries its own boxes, so playback needs neither a
    // sidecar nor the camera to be reachable.
    val frames by produceState(emptyMap<Long, DetectionFrame>(), file) {
        value = withContext(Dispatchers.IO) { DetectionTrackReader.read(file) }
    }
    var view by remember { mutableStateOf<VideoView?>(null) }
    var position by remember(file) { mutableLongStateOf(0L) }
    var duration by remember(file) { mutableLongStateOf(0L) }
    var paused by remember(file) { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var unplayable by remember(file) { mutableStateOf(false) }

    LaunchedEffect(view, file) {
        while (view != null) {
            if (scrubbing == null) position = view?.currentPosition?.toLong() ?: 0L
            delay(100)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        setOnPreparedListener { duration = it.duration.toLong(); start() }
                        setOnCompletionListener { paused = true }
                        // Returning true keeps the platform's own error dialog off the screen.
                        setOnErrorListener { _, _, _ -> unplayable = true; true }
                        view = this
                    }
                },
                update = { video ->
                    if (video.tag != file.absolutePath) {
                        video.tag = file.absolutePath
                        video.setVideoPath(file.absolutePath)
                    }
                },
            )
            if (showBoxes) {
                DetectionTrackReader.at(frames, position)?.let { BoxOverlay(it) }
            }
            if (loading) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = Ethan.colors.accent)
            if (unplayable) {
                Text("This clip cannot be played on this phone.", style = Ethan.type.body, color = Color.White)
            }
        }
        Rule()
        // Under the video, not over it: on some devices a VideoView blanks whatever is laid out above it.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = Gutter, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(R.drawable.lucide_x, "Close", onClick = onClose)
            Column(Modifier.weight(1f)) {
                Text(clip?.let(::clipTitle) ?: "Clip", style = Ethan.type.mono, color = Ethan.colors.textHeading, maxLines = 1)
                clip?.let { Text(clipMeta(it), style = Ethan.type.monoSmall, color = Ethan.colors.textMuted, maxLines = 1) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(position), style = Ethan.type.monoSmall, color = Ethan.colors.textMuted)
                EthanSlider(
                    value = scrubbing ?: if (duration > 0) position.toFloat() / duration else 0f,
                    onChange = { scrubbing = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    onChangeFinished = {
                        scrubbing?.let { view?.seekTo((it * duration).toInt()); position = (it * duration).toLong() }
                        scrubbing = null
                    },
                )
                Text(formatDuration(duration), style = Ethan.type.monoSmall, color = Ethan.colors.textMuted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.lucide_skip_back, "Earlier clip", enabled = onPrevious != null) { onPrevious?.invoke() }
                Spacer(Modifier.width(20.dp))
                IconAction(
                    if (paused) R.drawable.lucide_play else R.drawable.lucide_pause,
                    if (paused) "Play" else "Pause",
                    tint = Ethan.colors.textHeading,
                ) {
                    view?.let { video ->
                        if (paused) { if (position >= duration - 200) video.seekTo(0); video.start() } else video.pause()
                    }
                    paused = !paused
                }
                Spacer(Modifier.width(20.dp))
                IconAction(R.drawable.lucide_skip_forward, "Later clip", enabled = onNext != null) { onNext?.invoke() }
            }
        }
    }
}

@Composable
private fun BoxOverlay(frame: DetectionFrame) {
    val labelStyle = Ethan.type.monoSmall
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        frame.boxesList.forEach { box ->
            val left = box.left * size.width
            val top = box.top * size.height
            drawRect(
                color = BoxColor,
                topLeft = Offset(left, top),
                size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                style = Stroke(width = 3f),
            )
            drawText(
                measurer,
                "${box.label} ${(box.confidence * 100).toInt()}%",
                topLeft = Offset(left, (top - 36f).coerceAtLeast(0f)),
                style = labelStyle.copy(color = BoxColor),
            )
        }
    }
}

// Drawn over footage rather than paper, so it is the one colour not taken from the theme.
private val BoxColor = Color(0xFFFFD98A)
