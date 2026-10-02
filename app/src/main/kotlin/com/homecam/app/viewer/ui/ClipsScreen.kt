package com.homecam.app.viewer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homecam.app.R
import com.homecam.app.ui.formatBytes
import com.homecam.app.ui.formatDuration
import com.homecam.app.ui.kit.Badge
import com.homecam.app.ui.kit.ConfirmSheet
import com.homecam.app.ui.kit.DangerButton
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.FullScreen
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.IconAction
import com.homecam.app.ui.kit.ListRow
import com.homecam.app.ui.kit.LucideIcon
import com.homecam.app.ui.kit.QuietButton
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.SecondaryButton
import com.homecam.app.ui.kit.Sheet
import com.homecam.app.ui.kit.Tag
import com.homecam.app.ui.kit.Tone
import com.homecam.app.ui.kit.isLandscape
import com.homecam.app.ui.theme.Ethan
import com.homecam.app.ui.theme.EthanRadius
import com.homecam.app.ui.theme.HomecamTheme
import com.homecam.core.media.ClipFilter
import com.homecam.core.media.ClipRun
import com.homecam.core.media.ClipRuns
import com.homecam.core.media.Timeline
import com.homecam.proto.Clip
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the camera has recorded, as one recording you move through: a picture, a
 * ruler of time under it with what was detected marked on it, and the same footage as a
 * list below for finding a thing by what it was rather than when.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipsScreen(
    clips: List<Clip>?,
    playing: File?,
    playingId: String?,
    downloadingId: String?,
    projectedDays: Double?,
    onPlay: (Clip) -> Unit,
    onPin: (Clip) -> Unit,
    onDelete: (List<Clip>) -> Unit,
    onExport: (List<Clip>) -> Unit,
    showBoxes: Boolean,
    loadThumbnail: (suspend (String) -> ByteArray?)? = null,
    pinnedNearCeiling: Boolean = false,
    /** Leaves the camera; drawn only in landscape, where the shell's own bar is not. */
    onBack: () -> Unit = {},
) {
    if (clips == null) {
        EmptyState("Loading clips", busy = true)
        return
    }
    if (clips.isEmpty()) {
        EmptyState("Nothing recorded yet.", detail = "Clips appear here as the camera records them.", icon = R.drawable.lucide_film)
        return
    }

    val timeline = remember(clips) { Timeline(clips) }
    var playheadMs by remember { mutableLongStateOf(timeline.clips.last().startedAtMs) }
    var msPerDp by remember { mutableFloatStateOf(DEFAULT_MS_PER_DP) }
    var wantPlaying by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var seekToken by remember { mutableIntStateOf(0) }
    var seek by remember { mutableStateOf<Seek?>(null) }
    var unplayable by remember(playing) { mutableStateOf(false) }

    val playingClip = timeline.clips.firstOrNull { it.id == playingId }
    val underPlayhead = timeline.clipAt(playheadMs)

    /** Moves to an instant and plays from it; in a gap it only moves, and says so. */
    fun goTo(timeMs: Long, play: Boolean = true) {
        playheadMs = timeline.clamp(timeMs)
        val clip = timeline.clipAt(playheadMs)
        if (clip == null) {
            wantPlaying = false
            return
        }
        wantPlaying = play
        seek = Seek(++seekToken, playheadMs - clip.startedAtMs)
        if (clip.id != playingId || playing == null) onPlay(clip)
    }

    val panel = PlaybackState(
        timeline = timeline,
        playheadMs = playheadMs,
        msPerDp = msPerDp,
        clip = underPlayhead,
        file = playing?.takeIf { playingClip != null && playingClip.id == underPlayhead?.id },
        loading = downloadingId != null && downloadingId == underPlayhead?.id,
        playing = wantPlaying,
        seek = seek,
        unplayable = unplayable,
        showBoxes = showBoxes,
        loadThumbnail = loadThumbnail,
        onScrub = { scrubbing = true; wantPlaying = false; playheadMs = it },
        onScale = { msPerDp = it },
        onScrubEnd = { scrubbing = false; goTo(playheadMs) },
        onPosition = { position ->
            if (!scrubbing && wantPlaying && playingClip != null) playheadMs = playingClip.startedAtMs + position
        },
        onCompleted = {
            val next = playingClip?.let(timeline::continuationOf)
            if (next != null) goTo(next.startedAtMs) else wantPlaying = false
        },
        onUnplayable = { unplayable = true; wantPlaying = false },
        // From a gap, play picks up at the next thing that was recorded.
        onPlayPause = {
            if (wantPlaying) wantPlaying = false
            else goTo(underPlayhead?.let { playheadMs } ?: timeline.firstClipFrom(playheadMs)?.startedAtMs ?: playheadMs)
        },
        onJump = { deltaMs -> goTo(playheadMs + deltaMs, play = wantPlaying) },
        onPreviousEvent = { timeline.previousEvent(playheadMs)?.let { goTo(it.startedAtMs) } },
        onNextEvent = { timeline.nextEvent(playheadMs)?.let { goTo(it.startedAtMs) } },
        onDay = { day -> timeline.clips.firstOrNull { dayOf(it.startedAtMs) == day }?.let { goTo(it.startedAtMs, play = false) } },
    )

    if (isLandscape()) {
        FullScreen()
        // Footage is watched on a dark ground whatever the theme around it.
        HomecamTheme(dark = true) {
            Surface(Modifier.fillMaxSize(), color = Ethan.colors.bgSunken) { LandscapePlayback(panel, onBack) }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        PortraitPlayback(panel)
        Rule()
        ClipList(
            clips = clips,
            playingId = underPlayhead?.id,
            downloadingId = downloadingId,
            projectedDays = projectedDays,
            pinnedNearCeiling = pinnedNearCeiling,
            loadThumbnail = loadThumbnail,
            onOpen = { goTo(it.startedAtMs) },
            onPin = onPin,
            onDelete = onDelete,
            onExport = onExport,
        )
    }
}

private class PlaybackState(
    val timeline: Timeline,
    val playheadMs: Long,
    val msPerDp: Float,
    val clip: Clip?,
    val file: File?,
    val loading: Boolean,
    val playing: Boolean,
    val seek: Seek?,
    val unplayable: Boolean,
    val showBoxes: Boolean,
    val loadThumbnail: (suspend (String) -> ByteArray?)?,
    val onScrub: (Long) -> Unit,
    val onScale: (Float) -> Unit,
    val onScrubEnd: () -> Unit,
    val onPosition: (Long) -> Unit,
    val onCompleted: () -> Unit,
    val onUnplayable: () -> Unit,
    val onPlayPause: () -> Unit,
    val onJump: (Long) -> Unit,
    val onPreviousEvent: () -> Unit,
    val onNextEvent: () -> Unit,
    val onDay: (LocalDate) -> Unit,
)

private const val JUMP_MS = 10_000L

@Composable
private fun PortraitPlayback(state: PlaybackState) {
    Picture(state, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    Column(Modifier.fillMaxWidth().background(Ethan.colors.bgApp)) {
        Ruler(state)
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = Gutter), verticalAlignment = Alignment.CenterVertically) {
            DayButton(state)
            Transport(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LandscapePlayback(state: PlaybackState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Picture(state, Modifier.weight(1f).fillMaxWidth())
        Ruler(state, compact = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(R.drawable.lucide_arrow_left, "Back", onClick = onBack)
            DayButton(state)
            Transport(state, Modifier.weight(1f))
            Spacer(Modifier.width(Gutter))
        }
    }
}

@Composable
private fun Ruler(state: PlaybackState, compact: Boolean = false) {
    TimelineRuler(
        timeline = state.timeline,
        playheadMs = state.playheadMs,
        msPerDp = state.msPerDp,
        onScrub = state.onScrub,
        onScale = state.onScale,
        onScrubEnd = state.onScrubEnd,
        height = if (compact) 60.dp else 72.dp,
    )
}

/** The clip under the playhead: playing, or its still until it is, or a plain statement that there is none. */
@Composable
private fun Picture(state: PlaybackState, modifier: Modifier) {
    Box(
        modifier.background(Color.Black).clickable(enabled = state.clip != null, onClickLabel = "Play or pause", onClick = state.onPlayPause),
        contentAlignment = Alignment.Center,
    ) {
        val clip = state.clip
        when {
            clip == null -> Text("No footage was recorded at this time.", style = Ethan.type.body, color = Color.White)
            state.unplayable -> Text("This clip cannot be played on this phone.", style = Ethan.type.body, color = Color.White)
            state.file != null -> ClipVideo(
                file = state.file,
                playing = state.playing,
                seek = state.seek,
                showBoxes = state.showBoxes,
                onPosition = state.onPosition,
                onCompleted = state.onCompleted,
                onUnplayable = state.onUnplayable,
                modifier = Modifier.fillMaxSize(),
            )
            else -> state.loadThumbnail?.let { Still(clip.id, it) }
        }
        Box(Modifier.align(Alignment.TopStart).padding(10.dp)) { MomentBadge(state) }
        if (state.loading) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Ethan.colors.accent)
        } else if (clip != null && !state.playing && !state.unplayable) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Ethan.colors.accent),
                contentAlignment = Alignment.Center,
            ) { LucideIcon(R.drawable.lucide_play, "Play", size = 24.dp, tint = Ethan.colors.onAccent) }
        }
    }
}

@Composable
private fun Still(clipId: String, load: suspend (String) -> ByteArray?) {
    val image by produceState<ImageBitmap?>(null, clipId) {
        val bytes = withContext(Dispatchers.IO) { load(clipId) }
        value = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
}

@Composable
private fun MomentBadge(state: PlaybackState) {
    val clip = state.clip
    when {
        clip == null -> Badge("No footage", Tone.NEUTRAL)
        clip.pinned -> Badge("Pinned", Tone.NEUTRAL)
        clip.labelsCount > 0 -> Badge(clip.labelsList.joinToString(" · "), Tone.ACCENT, dot = true)
    }
}

@Composable
private fun DayButton(state: PlaybackState) {
    var choosing by remember { mutableStateOf(false) }
    val day = dayOf(state.playheadMs)
    Row(
        Modifier.clip(RoundedCornerShape(EthanRadius.control)).clickable { choosing = true }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dayLabel(day), style = Ethan.type.label, color = Ethan.colors.textHeading)
        Spacer(Modifier.width(6.dp))
        Text("▼", style = Ethan.type.caption, color = Ethan.colors.textAccent)
    }
    if (choosing) {
        val days = remember(state.timeline) {
            state.timeline.clips.groupBy { dayOf(it.startedAtMs) }.toSortedMap(compareByDescending { it })
        }
        Sheet(onDismiss = { choosing = false }) {
            Text("Go to a day", style = Ethan.type.title2, color = Ethan.colors.textHeading)
            Spacer(Modifier.height(6.dp))
            days.entries.forEachIndexed { index, (date, thatDay) ->
                if (index > 0) Rule()
                ListRow(
                    dayLabel(date),
                    meta = "${thatDay.size} clips · ${thatDay.count { it.labelsCount > 0 }} events",
                    metaMono = true,
                    onClick = { choosing = false; state.onDay(date) },
                ) { if (date == day) LucideIcon(R.drawable.lucide_check, "Showing", tint = Ethan.colors.textAccent) }
            }
        }
    }
}

@Composable
private fun Transport(state: PlaybackState, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconAction(R.drawable.lucide_chevrons_left, "Previous event", onClick = state.onPreviousEvent)
        IconAction(R.drawable.lucide_rewind, "Back 10 seconds") { state.onJump(-JUMP_MS) }
        IconAction(
            if (state.playing) R.drawable.lucide_pause else R.drawable.lucide_play,
            if (state.playing) "Pause" else "Play",
            tint = Ethan.colors.textHeading,
            onClick = state.onPlayPause,
        )
        IconAction(R.drawable.lucide_fast_forward, "Forward 10 seconds") { state.onJump(JUMP_MS) }
        IconAction(R.drawable.lucide_chevrons_right, "Next event", onClick = state.onNextEvent)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipList(
    clips: List<Clip>,
    playingId: String?,
    downloadingId: String?,
    projectedDays: Double?,
    pinnedNearCeiling: Boolean,
    loadThumbnail: (suspend (String) -> ByteArray?)?,
    onOpen: (Clip) -> Unit,
    onPin: (Clip) -> Unit,
    onDelete: (List<Clip>) -> Unit,
    onExport: (List<Clip>) -> Unit,
) {
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var pinnedOnly by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Clip?>(null) }
    var deleting by remember { mutableStateOf<List<Clip>?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }

    val labels = remember(clips) { clips.flatMap { it.labelsList }.groupingBy { it }.eachCount().toSortedMap() }
    val pinnedCount = remember(clips) { clips.count { it.pinned } }
    val shown = clips
        .let { if (activeLabel != null) ClipFilter.withLabel(it, activeLabel!!) else it }
        .let { if (pinnedOnly) it.filter { clip -> clip.pinned } else it }
    val runs = remember(shown) { ClipRuns.group(shown) }
    val chosen = clips.filter { it.id in selected }

    fun toggle(ids: List<String>) {
        selected = if (selected.containsAll(ids)) selected - ids.toSet() else selected + ids
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selecting) "${chosen.size} selected · ${formatBytes(chosen.sumOf { it.sizeBytes })}"
                else "${clips.size} clips · ${formatBytes(clips.sumOf { it.sizeBytes })}" +
                    // The number worth showing: the raw limits do not tell you how far back the
                    // camera can actually remember at the rate it is currently recording (§5).
                    (projectedDays?.let { " · ≈ %.0f d kept".format(it) } ?: ""),
                style = Ethan.type.monoSmall, color = Ethan.colors.textMuted, modifier = Modifier.weight(1f), maxLines = 1,
            )
            QuietButton(if (selecting) "Done" else "Select", { selecting = !selecting; selected = emptySet() })
        }
        if (pinnedNearCeiling) {
            Text(
                "Pinned clips are near the size limit, so new recordings are pushed out sooner.",
                style = Ethan.type.bodySmall,
                color = Ethan.colors.textDanger,
            )
        }
        if (labels.isNotEmpty() || pinnedCount > 0) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { (label, count) ->
                    Tag("$label $count", selected = activeLabel == label, onClick = { activeLabel = if (activeLabel == label) null else label })
                }
                if (pinnedCount > 0) Tag("pinned $pinnedCount", selected = pinnedOnly, onClick = { pinnedOnly = !pinnedOnly })
            }
        }

        if (runs.isEmpty()) {
            EmptyState("No clips match.", detail = "Clear the filter to see everything.", modifier = Modifier.height(160.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            runs.groupBy { dayOf(it.startedAtMs) }.forEach { (day, runsThatDay) ->
                stickyHeader(key = "day-$day") { DayHeader(day, runsThatDay.sumOf { it.clips.size }) }
                runsThatDay.forEach { run ->
                    val ids = run.clips.map { it.id }
                    if (run.clips.size == 1) {
                        val clip = run.clips.single()
                        item(key = clip.id) {
                            ClipRow(
                                clip, clip.id == playingId, downloadingId, loadThumbnail,
                                mark = if (selecting) clip.id in selected else null,
                                onClick = { if (selecting) toggle(ids) else onOpen(clip) },
                                onPin = onPin, onMenu = { menuFor = it },
                            )
                            Rule()
                        }
                    } else {
                        val open = run.startedAtMs in expanded
                        item(key = "run-${run.startedAtMs}") {
                            RunRow(
                                run, open, playingId in ids, loadThumbnail,
                                mark = if (selecting) selected.containsAll(ids) else null,
                                onMark = { toggle(ids) },
                                onToggle = { expanded = if (open) expanded - run.startedAtMs else expanded + run.startedAtMs },
                            )
                            Rule()
                        }
                        if (open) {
                            items(run.clips, key = { it.id }) { clip ->
                                ClipRow(
                                    clip, clip.id == playingId, downloadingId, loadThumbnail,
                                    mark = if (selecting) clip.id in selected else null,
                                    onClick = { if (selecting) toggle(listOf(clip.id)) else onOpen(clip) },
                                    onPin = onPin, onMenu = { menuFor = it }, indented = true,
                                )
                                Rule(Modifier.padding(start = 20.dp))
                            }
                        }
                    }
                }
            }
        }
        if (selecting && chosen.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Save ${chosen.size}", { onExport(chosen); selecting = false; selected = emptySet() }, Modifier.weight(1f), icon = R.drawable.lucide_download)
                DangerButton("Delete ${chosen.size}", { deleting = chosen }, Modifier.weight(1f), filled = false, icon = R.drawable.lucide_trash_2)
            }
        }
    }

    menuFor?.let { clip ->
        Sheet(onDismiss = { menuFor = null }) {
            Text(clipTitle(clip), style = Ethan.type.title2, color = Ethan.colors.textHeading)
            Text(clipMeta(clip), style = Ethan.type.monoSmall, color = Ethan.colors.textMuted)
            Spacer(Modifier.height(8.dp))
            ListRow("Play", leading = { LucideIcon(R.drawable.lucide_play, null) }, onClick = { menuFor = null; onOpen(clip) })
            Rule()
            ListRow(
                if (clip.pinned) "Unpin" else "Pin", meta = "A pinned clip is never deleted to make room",
                leading = { LucideIcon(R.drawable.lucide_pin, null) }, onClick = { menuFor = null; onPin(clip) },
            )
            Rule()
            ListRow("Save to gallery", leading = { LucideIcon(R.drawable.lucide_download, null) }, onClick = { menuFor = null; onExport(listOf(clip)) })
            Rule()
            ListRow(
                "Delete", leading = { LucideIcon(R.drawable.lucide_trash_2, null, tint = Ethan.colors.textDanger) },
                onClick = { menuFor = null; deleting = listOf(clip) },
            )
        }
    }
    deleting?.let { doomed ->
        ConfirmSheet(
            title = if (doomed.size == 1) "Delete this clip?" else "Delete ${doomed.size} clips?",
            body = (if (doomed.size == 1) clipTitle(doomed.single()) + " is" else "${formatBytes(doomed.sumOf { it.sizeBytes })} of footage is") +
                " removed from the camera. This cannot be undone.",
            confirm = "Delete",
            onConfirm = { onDelete(doomed); selecting = false; selected = emptySet() },
            onDismiss = { deleting = null },
        )
    }
}

private fun dayOf(atMs: Long): LocalDate = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate()

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val clockWithSeconds = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dayName = DateTimeFormatter.ofPattern("EEEE d MMMM")

private fun clockOf(atMs: Long): String = clock.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

private fun clockSeconds(atMs: Long): String =
    clockWithSeconds.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dayName.format(day)
    }
}

private fun clipTitle(clip: Clip): String = clockSeconds(clip.startedAtMs) + clip.labelsList.joinToString("") { " · $it" }

private fun clipMeta(clip: Clip): String =
    "${formatDuration(clip.durationMs.toLong())} · ${formatBytes(clip.sizeBytes)} · ${minOf(clip.width, clip.height)}p"

@Composable
private fun DayHeader(day: LocalDate, count: Int) {
    Row(
        Modifier.fillMaxWidth().background(Ethan.colors.bgApp).padding(top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dayLabel(day).uppercase(), style = Ethan.type.micro, color = Ethan.colors.textFaint, modifier = Modifier.weight(1f))
        Text("$count CLIPS", style = Ethan.type.micro, color = Ethan.colors.textFaint)
    }
}

/** In selection the leading mark replaces nothing: it is added, so the row keeps its shape. */
@Composable
private fun SelectionMark(marked: Boolean, onClick: () -> Unit) {
    IconAction(
        if (marked) R.drawable.lucide_circle_check else R.drawable.lucide_circle,
        if (marked) "Selected" else "Not selected",
        tint = if (marked) Ethan.colors.accent else Ethan.colors.borderStrong,
        onClick = onClick,
    )
}

/** A stretch of back-to-back clips, shown as one line until it is opened. */
@Composable
private fun RunRow(
    run: ClipRun,
    open: Boolean,
    current: Boolean,
    loadThumbnail: (suspend (String) -> ByteArray?)?,
    mark: Boolean?,
    onMark: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(if (current && !open) Ethan.colors.surfaceAccentSoft else Color.Transparent)
            .clickable(onClick = onToggle).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) SelectionMark(mark, onMark)
        loadThumbnail?.let {
            ClipThumbnail((run.clips.firstOrNull { clip -> clip.labelsCount > 0 } ?: run.clips.first()).id, it)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${clockOf(run.startedAtMs)}–${clockOf(run.endedAtMs)}" + run.labels.joinToString("") { " · $it" },
                style = Ethan.type.mono.copy(fontWeight = FontWeight.W500),
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

/** Tapping the row plays it from the timeline; everything else is behind the pin and the menu. */
@Composable
private fun ClipRow(
    clip: Clip,
    current: Boolean,
    downloadingId: String?,
    loadThumbnail: (suspend (String) -> ByteArray?)?,
    mark: Boolean?,
    onClick: () -> Unit,
    onPin: (Clip) -> Unit,
    onMenu: (Clip) -> Unit,
    indented: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) Ethan.colors.surfaceAccentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = if (indented) 20.dp else 0.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) SelectionMark(mark, onClick)
        loadThumbnail?.let {
            ClipThumbnail(clip.id, it)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                clockOf(clip.startedAtMs) + clip.labelsList.joinToString("") { " · $it" },
                style = Ethan.type.mono.copy(fontWeight = FontWeight.W500),
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
        if (mark == null) {
            IconAction(
                R.drawable.lucide_pin,
                if (clip.pinned) "Unpin" else "Pin",
                tint = if (clip.pinned) Ethan.colors.textAccent else Ethan.colors.borderStrong,
            ) { onPin(clip) }
            IconAction(R.drawable.lucide_ellipsis_vertical, "More") { onMenu(clip) }
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

