package com.homecam.app.camera.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.homecam.app.R
import com.homecam.app.ui.HardwareProtectionControl
import com.homecam.app.ui.RecordingModeControl
import com.homecam.app.ui.kit.Badge
import com.homecam.app.ui.kit.Chevron
import com.homecam.app.ui.kit.ConfirmSheet
import com.homecam.app.ui.kit.Eyebrow
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.ListRow
import com.homecam.app.ui.kit.LucideIcon
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.kit.PrimaryButton
import com.homecam.app.ui.kit.QuietButton
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.SwitchRow
import com.homecam.app.ui.kit.Tone
import com.homecam.app.ui.kit.TopBar
import com.homecam.app.ui.theme.Ethan
import com.homecam.app.ui.theme.EthanRadius
import com.homecam.core.health.HealthConfig
import com.homecam.app.ui.LensSelector
import com.homecam.proto.HardwareProtection
import com.homecam.proto.LensInfo
import com.homecam.proto.RecordingMode

/**
 * §2.6 puts the access count above the fold, and §2.7 puts a live indicator on the camera
 * device itself. Both are here for the same reason: the person walking past the phone in
 * the hallway is the one who most needs to know somebody is watching.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraHomeScreen(
    alias: String,
    accessCount: Int,
    liveViewerLabel: String?,
    serving: Boolean,
    armed: Boolean,
    hardwareBacked: Boolean,
    recordingMode: RecordingMode,
    projectedDays: Double?,
    onArmedChange: (Boolean) -> Unit,
    onRecordingMode: (RecordingMode) -> Unit,
    protection: HealthConfig,
    onProtection: (HardwareProtection) -> Unit,
    onManage: () -> Unit,
    onPair: () -> Unit,
    onLog: () -> Unit,
    onNetwork: () -> Unit,
    probing: Boolean = false,
    onLensProbe: () -> Unit = {},
    lenses: List<LensInfo> = emptyList(),
    lens: String = "",
    onLens: (String) -> Unit = {},
    onSwitchRole: () -> Unit = {},
    canDrawOverlays: Boolean = true,
    onAllowOverlay: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(alias, subtitle = "This phone is the camera", subtitleIsData = false) {
            var confirmProbe by remember { mutableStateOf(false) }
            Badge(
                if (probing) "Probing lenses" else if (serving) "Running" else "Stopped",
                if (probing) Tone.WARNING else if (serving) Tone.SUCCESS else Tone.DANGER,
                dot = true,
                // Hidden on purpose: the probe takes the camera offline for minutes.
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { if (!probing) confirmProbe = true }),
            )
            if (confirmProbe) {
                ConfirmSheet(
                    title = "Probe the lenses?",
                    body = "The camera stops recording while it runs two lenses at once, for up to 20 minutes, " +
                        "then starts again by itself. The result goes to the diagnostics log.",
                    confirm = "Run probe",
                    onConfirm = onLensProbe,
                    onDismiss = { confirmProbe = false },
                    danger = false,
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Gutter)) {
            Spacer(Modifier.height(12.dp))
            WatchingBanner(liveViewerLabel)

            Eyebrow("Watching")
            PaperCard(padding = 0.dp) {
                SwitchRow(
                    if (armed) "Armed" else "Disarmed", armed, onArmedChange,
                    meta = if (armed) "Detecting, recording events and alerting" else "Not detecting. Nothing is recorded or alerted",
                )
            }

            if (lenses.size > 1) {
                Eyebrow("Lens")
                PaperCard { LensSelector(lenses, lens, onLens) }
            }

            Eyebrow("Recording")
            PaperCard(padding = 0.dp) { RecordingModeControl(recordingMode, projectedDays, onRecordingMode) }

            Eyebrow("Protection")
            PaperCard(padding = 0.dp) { HardwareProtectionControl(protection, onProtection) }

            Eyebrow("Access")
            PaperCard(padding = 0.dp) {
                ListRow(
                    "$accessCount ${if (accessCount == 1) "device has" else "devices have"} access",
                    meta = "See who, and revoke",
                    onClick = onManage,
                    leading = { LucideIcon(R.drawable.lucide_users, null) },
                ) { Chevron() }
                Rule()
                ListRow("Access log", meta = "Every connection, with a tamper check", onClick = onLog,
                    leading = { LucideIcon(R.drawable.lucide_scroll_text, null) }) { Chevron() }
                Rule()
                ListRow("Network activity", meta = "Everything the app sends", onClick = onNetwork,
                    leading = { LucideIcon(R.drawable.lucide_activity, null) }) { Chevron() }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Pair a device", onPair, Modifier.fillMaxWidth(), icon = R.drawable.lucide_qr_code)

            if (!canDrawOverlays) {
                Eyebrow("Needs attention")
                PaperCard {
                    Text("Resume after a restart", style = Ethan.type.bodyStrong, color = Ethan.colors.textHeading)
                    Text(
                        "Android keeps the camera off after a restart until something is on screen. " +
                            "Allowing display over other apps lets it recover by itself.",
                        style = Ethan.type.bodySmall,
                        color = Ethan.colors.textMuted,
                    )
                    QuietButton("Allow automatic resume", onAllowOverlay)
                }
            }

            Eyebrow("This phone")
            PaperCard(padding = 0.dp) {
                ListRow("Use this phone as a viewer", meta = "Stops the camera. Pairings and identity are kept", onClick = onSwitchRole,
                    leading = { LucideIcon(R.drawable.lucide_repeat, null) }) { Chevron() }
                Rule()
                ListRow(
                    "Identity key",
                    meta = if (hardwareBacked) "Held in this phone's secure hardware" else "Held in software; this phone has no hardware keystore",
                    leading = { LucideIcon(R.drawable.lucide_lock, null) },
                ) { Badge(if (hardwareBacked) "Hardware" else "Software", if (hardwareBacked) Tone.SUCCESS else Tone.WARNING) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Always on screen, so "nobody" is a statement and not an absence. */
@Composable
private fun WatchingBanner(liveViewerLabel: String?) {
    val live = liveViewerLabel != null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EthanRadius.card))
            .background(if (live) Ethan.colors.accent else Ethan.colors.bgSunken)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(R.drawable.lucide_eye, null, tint = if (live) Ethan.colors.onAccent else Ethan.colors.textFaint)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                if (live) "Being watched now" else "Nobody is watching",
                style = Ethan.type.title3,
                color = if (live) Ethan.colors.onAccent else Ethan.colors.textHeading,
            )
            if (live) Text(liveViewerLabel.orEmpty(), style = Ethan.type.monoSmall, color = Ethan.colors.onAccent)
        }
    }
}
