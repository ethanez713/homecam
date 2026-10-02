package com.homecam.app.viewer.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.homecam.app.ui.kit.FullScreen
import com.homecam.app.ui.kit.isLandscape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.homecam.app.ui.formatBytes
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
import com.homecam.app.ui.kit.Stat
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
    ) : LiveState
    data class Failed(val message: String) : LiveState
}

data class LiveControls(
    val torchOn: Boolean,
    val micOn: Boolean,
    val zoom: Float,
    val onTorch: (Boolean) -> Unit,
    val onMic: (Boolean) -> Unit,
    val onZoom: (Float) -> Unit,
    val onRotate: () -> Unit = {},
    val onSnapshot: () -> Unit = {},
    // Hidden when the camera's capabilities say it has no flash / no speaker (Phase 2).
    val torchAvailable: Boolean = true,
    val pushToTalkAvailable: Boolean = true,
    // The siren is a deliberate action, kept apart from the ordinary controls (Phase 8).
    val sirenAvailable: Boolean = false,
    val sirenOn: Boolean = false,
    val onSiren: (Boolean) -> Unit = {},
)

@Composable
fun LiveScreen(
    state: LiveState,
    controls: LiveControls?,
    onRetry: () -> Unit,
    /** Offered when the connection fails: the address lives on this phone, so it needs no camera. */
    onSetRemoteAddress: (() -> Unit)? = null,
    /** Leaves the camera; drawn only where the shell's own bar is not, in landscape. */
    onBack: () -> Unit = {},
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

        is LiveState.Connected ->
            if (state.video != null || videoPlaceholder != null) LiveVideo(state, controls, videoPlaceholder, onBack)
            else Waiting(state)
    }
}

@Composable
private fun Waiting(state: LiveState.Connected) {
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
            PaperCard { CameraStats(it) }
        }
    }
}

@Composable
private fun LiveVideo(
    state: LiveState.Connected,
    controls: LiveControls?,
    videoPlaceholder: (@Composable () -> Unit)?,
    onBack: () -> Unit,
) {
    val picture: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(Color.Black)) {
            if (state.video != null) VideoSurface(state.video, Modifier.fillMaxSize()) else videoPlaceholder?.invoke()
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge("Live", Tone.ACCENT, dot = true)
                if (state.via.isNotBlank()) Badge(state.via, Tone.NEUTRAL)
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
                CameraStats(it)
                Spacer(Modifier.height(10.dp))
            }
            controls?.let { Controls(it) }
        }
    }
}

@Composable
private fun CameraStats(status: RuntimeStatus) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Battery", "%.0f%%%s".format(status.batteryPercent, if (status.charging) " charging" else ""))
        Stat("Temp", "%.1f °C".format(status.batteryTempC))
        Stat("Free", formatBytes(status.freeBytes))
        Stat("Clips", formatBytes(status.clipBytes))
    }
}

@Composable
private fun Controls(controls: LiveControls) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("ZOOM", style = Ethan.type.micro, color = Ethan.colors.textFaint)
        EthanSlider(controls.zoom, controls.onZoom, Modifier.weight(1f).padding(horizontal = 12.dp))
        Text("%3.0f%%".format(controls.zoom * 100), style = Ethan.type.monoSmall, color = Ethan.colors.textMuted)
    }
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
