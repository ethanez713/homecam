package com.homecam.app.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.app.R
import com.homecam.app.data.CameraEntity
import com.homecam.app.ui.formatAgo
import com.homecam.app.ui.kit.Badge
import com.homecam.app.ui.kit.Chevron
import com.homecam.app.ui.kit.Tone
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.Eyebrow
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.IconAction
import com.homecam.app.ui.kit.ListRow
import com.homecam.app.ui.kit.LucideIcon
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.kit.PrimaryButton
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.TopBar
import com.homecam.app.ui.theme.Ethan

@Composable
fun ViewerHomeScreen(
    cameras: List<CameraEntity>,
    onAdd: () -> Unit,
    onOpen: (CameraEntity) -> Unit,
    onForget: (String) -> Unit,
    onLog: () -> Unit,
    onNetwork: () -> Unit,
    onSwitchRole: () -> Unit = {},
    starred: String? = null,
    onStar: (String) -> Unit = {},
) {

    Column(Modifier.fillMaxSize()) {
        TopBar("Cameras") {
            IconAction(R.drawable.lucide_plus, "Add a camera", tint = Ethan.colors.textAccent, onClick = onAdd)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Gutter)) {
            if (cameras.isEmpty()) {
                EmptyState(
                    line = "No cameras yet.",
                    detail = "Start pairing on the camera phone, then scan its code from here.",
                    icon = R.drawable.lucide_cctv,
                    modifier = Modifier.height(280.dp),
                ) { PrimaryButton("Add a camera", onAdd, icon = R.drawable.lucide_qr_code) }
            } else {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cameras.forEach { camera ->
                        CameraCard(camera, camera.fingerprint == starred, cameras.size > 1, { onOpen(camera) }, { onStar(camera.fingerprint) })
                    }
                }
            }

            Eyebrow("This phone")
            PaperCard(padding = 0.dp) {
                ListRow("Access log", meta = "Connections made from this phone", onClick = onLog,
                    leading = { LucideIcon(R.drawable.lucide_scroll_text, null) }) { Chevron() }
                Rule()
                ListRow("Network activity", meta = "Everything the app sends", onClick = onNetwork,
                    leading = { LucideIcon(R.drawable.lucide_activity, null) }) { Chevron() }
                Rule()
                ListRow("Use this phone as a camera", meta = "Pairings and identity are kept", onClick = onSwitchRole,
                    leading = { LucideIcon(R.drawable.lucide_repeat, null) }) { Chevron() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CameraCard(camera: CameraEntity, starred: Boolean, canStar: Boolean, onOpen: () -> Unit, onStar: () -> Unit) {
    PaperCard(padding = 0.dp, onClick = onOpen) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            LucideIcon(R.drawable.lucide_cctv, null, size = 22.dp, tint = Ethan.colors.textAccent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(camera.alias, style = Ethan.type.title2, color = Ethan.colors.textHeading, maxLines = 1)
                    if (camera.lastArmed >= 0) {
                        Spacer(Modifier.width(8.dp))
                        Badge(
                            if (camera.lastArmed == 1) "Armed" else "Disarmed",
                            if (camera.lastArmed == 1) Tone.SUCCESS else Tone.WARNING,
                            dot = true,
                        )
                    }
                }
                Text(camera.fingerprint, style = Ethan.type.monoSmall, color = Ethan.colors.textMuted, maxLines = 1)
                Text(
                    listOfNotNull(
                        camera.lastRecordingMode.takeIf { it.isNotBlank() }?.let { "$it recording" },
                        "seen ${formatAgo(camera.lastSeenMs)}",
                    ).joinToString(" · ").replaceFirstChar { it.uppercase() },
                    style = Ethan.type.caption,
                    color = Ethan.colors.textFaint,
                    maxLines = 1,
                )
            }
            // With one camera there is nothing to choose between, so no star is drawn.
            if (canStar) {
                IconAction(
                    R.drawable.lucide_star,
                    if (starred) "Opens when the app starts" else "Open this camera when the app starts",
                    tint = if (starred) Ethan.colors.accent else Ethan.colors.borderStrong,
                    onClick = onStar,
                )
            }
            Chevron()
            Spacer(Modifier.width(10.dp))
        }
    }
}
