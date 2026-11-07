package com.homecam.app.viewer.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.homecam.app.ui.kit.FullScreen
import com.homecam.app.ui.kit.isLandscape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.homecam.app.R
import com.homecam.app.ui.LensSelector
import com.homecam.app.ui.SecondLens
import com.homecam.core.lens.LensSelections
import com.homecam.app.ui.RecordingPanel
import com.homecam.app.ui.RecordingPanelState
import com.homecam.app.ui.formatBytes
import com.homecam.core.health.HealthItem
import com.homecam.core.lens.ZoomMap
import com.homecam.proto.LensInfo
import com.homecam.app.ui.kit.Badge
import com.homecam.app.ui.kit.ConfirmSheet
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.EthanSlider
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.LucideIcon
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.kit.PrimaryButton
import com.homecam.app.ui.kit.QuietButton
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.Segmented
import com.homecam.app.ui.kit.Stat
import com.homecam.app.ui.kit.Tag
import com.homecam.app.ui.kit.Tone
import com.homecam.app.ui.theme.Ethan
import com.homecam.app.ui.theme.EthanRadius
import com.homecam.proto.Presence
import com.homecam.proto.RuntimeStatus
import org.webrtc.VideoTrack

sealed interface LiveState {
    data class Locating(val alias: String) : LiveState
    data class Connecting(val host: String, val port: Int) : LiveState
    data class Connected(
        val phrase: String,
        val presence: Presence?,
        val status: RuntimeStatus?,
        val video: VideoTrack?,
        /** "Home network" or "Remote": which path this session took to the camera. */
        val via: String = "",
        /** Every lens track in the session, keyed by lens id. [video] is the primary picture. */
        val videos: Map<String, VideoTrack> = emptyMap(),
    ) : LiveState
    data class Failed(val message: String) : LiveState
    data object Privacy : LiveState
}

data class LiveControls(
    val torchOn: Boolean,
    val micOn: Boolean,
    val zoom: Float,
    val onTorch: (Boolean) -> Unit,
    val onMic: (Boolean) -> Unit,
    val onZoom: (Float) -> Unit,
    /** 0 keeps the capture size. 720 and 540 scale the live picture down to that height. */
    val liveQuality: Int = 0,
    val onLiveQuality: (Int) -> Unit = {},
    val onRotate: () -> Unit = {},
    val onSnapshot: () -> Unit = {},
    val lenses: List<LensInfo> = emptyList(),
    val lens: String = "",
    val onLens: (String) -> Unit = {},
    // Hidden when the camera's capabilities say it has no flash / no speaker (Phase 2).
    val torchAvailable: Boolean = true,
    val pushToTalkAvailable: Boolean = true,
    // The siren is a deliberate action, kept apart from the ordinary controls (Phase 8).
    val sirenAvailable: Boolean = false,
    val sirenOn: Boolean = false,
    val onSiren: (Boolean) -> Unit = {},
    val recording: RecordingPanelState? = null,
    val health: List<HealthItem> = emptyList(),
    /** The selected second lens, when one is recording beside the primary. */
    val secondaryLens: String? = null,
    val secondLens: SecondLens? = null,
)

@Composable
fun LiveScreen(
    state: LiveState,
    controls: LiveControls?,
    onRetry: () -> Unit,
    onTurnOffPrivacy: () -> Unit = {},
    /** Offered when the connection fails: the address lives on this phone, so it needs no camera. */
    onSetRemoteAddress: (() -> Unit)? = null,
    /** Leaves the camera; drawn only where the shell's own bar is not, in landscape. */
    onBack: () -> Unit = {},
    /** Lens ids currently on screen. Hiding one stops its sender; recording continues. */
    onShownLenses: (List<String>) -> Unit = {},
    /** Long-press on the primary picture. [x] and [y] are 0..1 on that upright frame. */
    onFocus: ((x: Float, y: Float) -> Unit)? = null,
    /** Stands in for the video where there is no track to render, as in a preview. */
    videoPlaceholder: (@Composable () -> Unit)? = null,
) {
    when (state) {
        is LiveState.Locating -> EmptyState("Finding ${state.alias}", busy = true)
        is LiveState.Connecting -> EmptyState("Connecting", detail = "${state.host}:${state.port}", busy = true)
        is LiveState.Failed -> EmptyState(
            line = "Not connected",
            detail = state.message,
            icon = R.drawable.lucide_circle_alert,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryButton("Try again", onRetry, icon = R.drawable.lucide_refresh_cw)
                if (onSetRemoteAddress != null) QuietButton("Set remote address", onSetRemoteAddress)
            }
        }

        is LiveState.Privacy -> EmptyState(
            line = "Privacy mode is on",
            icon = R.drawable.lucide_video,
        ) {
            PrimaryButton("Turn off", onTurnOffPrivacy)
        }

        is LiveState.Connected ->
            if (state.video != null || state.videos.isNotEmpty() || videoPlaceholder != null) {
                LiveVideo(state, controls, videoPlaceholder, onBack, onShownLenses, onFocus)
            } else {
                Waiting(state, controls)
            }
    }
}

@Composable
private fun Waiting(state: LiveState.Connected, controls: LiveControls?) {
    Column(Modifier.fillMaxSize().padding(Gutter), verticalArrangement = Arrangement.Center) {
        EmptyState(
            line = when (state.presence?.state) {
                Presence.State.BUSY -> "The camera is busy with another device"
                Presence.State.DISABLED -> "The camera is switched off"
                else -> "Waiting for the first frame"
            },
            busy = state.presence?.state != Presence.State.DISABLED,
            modifier = Modifier.height(160.dp),
        )
        PaperCard {
            Text("VERIFICATION PHRASE", style = Ethan.type.micro, color = Ethan.colors.textFaint)
            Text(state.phrase, style = Ethan.type.monoLarge, color = Ethan.colors.textHeading)
            Text(
                "The same words show on the camera while you pair.",
                style = Ethan.type.bodySmall,
                color = Ethan.colors.textMuted,
            )
        }
        state.status?.let {
            Spacer(Modifier.height(8.dp))
            PaperCard { CameraStats(it, controls?.health.orEmpty()) }
        }
        controls?.recording?.let {
            Spacer(Modifier.height(8.dp))
            RecordingPanel(it)
        }
    }
}

@Composable
private fun LiveVideo(
    state: LiveState.Connected,
    controls: LiveControls?,
    videoPlaceholder: (@Composable () -> Unit)?,
    onBack: () -> Unit,
    onShownLenses: (List<String>) -> Unit,
    onFocus: ((x: Float, y: Float) -> Unit)?,
) {
    val primaryId = controls?.lens?.takeIf { it.isNotEmpty() }.orEmpty()
    val secondaryId = controls?.secondaryLens?.takeIf { it.isNotEmpty() }
    var swapped by remember(primaryId, secondaryId) { mutableStateOf(false) }
    var userClosed by remember(primaryId, secondaryId) { mutableStateOf(false) }
    val bigId = if (swapped && secondaryId != null) secondaryId else primaryId
    val smallId = if (swapped && secondaryId != null) primaryId else secondaryId
    val paused = state.status?.secondaryPaused == true
    val shown = when {
        secondaryId == null -> listOf(primaryId)
        userClosed -> listOf(bigId)
        else -> listOf(primaryId, secondaryId)
    }.filter { it.isNotEmpty() }
    val shownKey = shown.sorted().joinToString("\u0000")
    LaunchedEffect(shownKey) { if (shown.isNotEmpty()) onShownLenses(shown.sorted()) }

    val picture: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(Color.Black)) {
            val big = trackFor(state, bigId, primaryId, secondaryId)
            val focusAt = if (bigId == primaryId) onFocus else null
            if (big != null) VideoSurface(big, Modifier.fillMaxSize(), onLongPress = focusAt) else videoPlaceholder?.invoke()
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge("Live", Tone.ACCENT, dot = true)
                if (state.via.isNotBlank()) Badge(state.via, Tone.NEUTRAL)
            }
            val pip = smallId?.let { trackFor(state, it, primaryId, secondaryId) }
            val showPip = secondaryId != null && smallId != null && !userClosed && !paused &&
                (pip != null || videoPlaceholder != null)
            if (showPip) {
                LensPip(pip, videoPlaceholder, onSwap = { swapped = !swapped }, onClose = { userClosed = true })
            } else if (secondaryId != null && userClosed && !paused) {
                Tag(
                    "Show ${LensSelections.labelOf(secondaryId)}",
                    selected = false,
                    onClick = { userClosed = false },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                )
            }
        }
    }

    if (isLandscape()) {
        // The camera usually lies on its side, so this is the natural way to watch it: the
        // picture takes the screen and the keys stand in a rail beside it.
        FullScreen()
        Row(Modifier.fillMaxSize()) {
            picture(Modifier.weight(1f).fillMaxHeight())
            Column(
                Modifier.fillMaxHeight().width(76.dp).background(Ethan.colors.bgApp)
                    .verticalScroll(rememberScrollState()).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ControlKey(R.drawable.lucide_arrow_left, "Back", Modifier.fillMaxWidth(), onClick = onBack)
                controls?.let { Keys(it, Modifier.fillMaxWidth()) }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        picture(Modifier.weight(1f).fillMaxWidth())
        Rule()
        Column(Modifier.fillMaxWidth().background(Ethan.colors.bgApp).padding(horizontal = Gutter, vertical = 10.dp)) {
            state.status?.let {
                CameraStats(it, controls?.health.orEmpty())
                Spacer(Modifier.height(10.dp))
            }
            controls?.recording?.let {
                RecordingPanel(it)
                Spacer(Modifier.height(10.dp))
            }
            controls?.let { Controls(it) }
        }
    }
}

/** The other lens, small, in the corner that does not cover the live badge. */
@Composable
private fun BoxScope.LensPip(
    track: VideoTrack?,
    placeholder: (@Composable () -> Unit)?,
    onSwap: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(10.dp)
            .width(132.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(EthanRadius.card))
            .background(Color.Black)
            .border(1.dp, Ethan.colors.borderSubtle, RoundedCornerShape(EthanRadius.card)),
    ) {
        if (track != null) VideoSurface(track, Modifier.fillMaxSize()) else placeholder?.invoke()
        Box(Modifier.fillMaxSize().clickable(onClick = onSwap))
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(30.dp)
                .clip(RoundedCornerShape(EthanRadius.control))
                .background(Ethan.colors.surfaceCard)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(R.drawable.lucide_x, "Hide", size = 16.dp, tint = Ethan.colors.textBody)
        }
    }
}

/**
 * The track for [lensId]. A one-lens session may deliver its only track under another key;
 * a second lens never borrows the other picture, or a swap would show the same view twice.
 */
private fun trackFor(state: LiveState.Connected, lensId: String, primaryId: String, secondaryId: String?): VideoTrack? {
    val videos = state.videos
    videos[lensId]?.let { return it }
    // The primary's track keeps the stream id of the lens the session opened on: a primary
    // switch changes what it films, not the track.
    if (lensId == primaryId) videos.entries.firstOrNull { it.key != secondaryId }?.let { return it.value }
    if (secondaryId != null) return null
    return state.video
}

@Composable
private fun CameraStats(status: RuntimeStatus, health: List<HealthItem>) {
    if (health.isEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Battery", "%.0f%%%s".format(status.batteryPercent, if (status.charging) " charging" else ""))
            Stat("Temp", "%.1f °C".format(status.batteryTempC))
            Stat("Free", formatBytes(status.freeBytes))
            Stat("Clips", formatBytes(status.clipBytes))
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        health.forEach { item ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.label.uppercase(), style = Ethan.type.micro, color = Ethan.colors.textFaint)
                Text(item.value, style = Ethan.type.monoSmall, color = Ethan.colors.textBody)
            }
        }
    }
}

@Composable
private fun Controls(controls: LiveControls) {
    LensSelector(
        controls.lenses,
        controls.lens,
        controls.onLens,
        Modifier.padding(bottom = 8.dp),
        secondLens = controls.secondLens,
    )
    val range = controls.lenses.firstOrNull { it.id == controls.lens }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("ZOOM", style = Ethan.type.micro, color = Ethan.colors.textFaint)
        EthanSlider(controls.zoom, controls.onZoom, Modifier.weight(1f).padding(horizontal = 12.dp))
        Text(
            range?.let { ZoomMap.label(ZoomMap.factorAt(controls.zoom, it.zoomMin, it.zoomMax)) } ?: "%3.0f%%".format(controls.zoom * 100),
            style = Ethan.type.monoSmall,
            color = Ethan.colors.textMuted,
        )
    }
    Text("LIVE", style = Ethan.type.micro, color = Ethan.colors.textFaint, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
    Segmented(
        listOf(0 to "Auto", 720 to "720p", 540 to "540p"),
        controls.liveQuality,
        controls.onLiveQuality,
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Keys(controls, Modifier.weight(1f))
    }
}

/** The same keys in a row under the picture or a rail beside it; [each] sizes one of them. */
@Composable
private fun Keys(controls: LiveControls, each: Modifier) {
    var confirmSiren by remember { mutableStateOf(false) }

    ControlKey(R.drawable.lucide_rotate_cw, "Rotate", each, onClick = controls.onRotate)
    ControlKey(R.drawable.lucide_camera, "Snap", each, onClick = controls.onSnapshot)
    if (controls.torchAvailable) {
        ControlKey(
            if (controls.torchOn) R.drawable.lucide_flashlight else R.drawable.lucide_flashlight_off,
            "Torch", each, on = controls.torchOn,
        ) { controls.onTorch(!controls.torchOn) }
    }
    if (controls.pushToTalkAvailable) {
        ControlKey(
            if (controls.micOn) R.drawable.lucide_mic else R.drawable.lucide_mic_off,
            "Talk", each, on = controls.micOn,
        ) { controls.onMic(!controls.micOn) }
    }
    if (controls.sirenAvailable) {
        ControlKey(
            R.drawable.lucide_siren, if (controls.sirenOn) "Stop" else "Siren",
            each, on = controls.sirenOn, danger = true,
        ) { if (controls.sirenOn) controls.onSiren(false) else confirmSiren = true }
    }

    if (confirmSiren) {
        ConfirmSheet(
            title = "Sound the siren?",
            body = "The camera plays a loud alarm for 30 seconds. You can stop it from here.",
            confirm = "Sound siren",
            onConfirm = { controls.onSiren(true) },
            onDismiss = { confirmSiren = false },
        )
    }
}

/** A labelled key: the glyph says what, the word removes the guess. */
@Composable
private fun ControlKey(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Ethan.colors
    val fill = when {
        on && danger -> colors.danger
        on -> colors.accentQuiet
        else -> colors.surfaceCard
    }
    val ink = when {
        on && danger -> colors.onDanger
        on -> colors.onAccentSoft
        danger -> colors.textDanger
        else -> colors.textBody
    }
    val shape = RoundedCornerShape(EthanRadius.control)
    Column(
        modifier
            .height(58.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (on) Color.Transparent else colors.borderSubtle, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LucideIcon(icon, null, size = 20.dp, tint = ink)
        Spacer(Modifier.height(3.dp))
        Text(label, style = Ethan.type.caption, color = ink, maxLines = 1)
    }
}
