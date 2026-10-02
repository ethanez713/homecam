package com.homecam.app.viewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.formatAgo
import com.homecam.app.ui.formatStamp
import com.homecam.app.ui.kit.ConfirmSheet
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.Eyebrow
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.TopBar
import com.homecam.app.ui.kit.DeviceRow
import com.homecam.proto.Viewer

/**
 * §2.6, read over the wire: the same roster the camera shows, so the camera can be audited
 * from the phone in your pocket rather than only from the hallway. Revoke works from here
 * too, including revoking the device you are holding.
 */
@Composable
fun RemoteRosterScreen(
    viewers: List<Viewer>?,
    selfFingerprint: String,
    onRevoke: (String) -> Unit,
    onBack: () -> Unit,
) {
    var revoking by remember { mutableStateOf<Viewer?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Paired devices", onBack = onBack)
        if (viewers == null) {
            EmptyState("Asking the camera who has access", busy = true)
            return@Column
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Gutter)) {
            Eyebrow("${viewers.size} ${if (viewers.size == 1) "device has" else "devices have"} access")
            PaperCard(padding = 0.dp) {
                viewers.forEachIndexed { index, viewer ->
                    if (index > 0) Rule()
                    DeviceRow(
                        label = viewer.label,
                        self = viewer.fingerprint == selfFingerprint,
                        fingerprint = viewer.fingerprint,
                        detail = "Paired ${formatStamp(viewer.pairedAtMs)} · seen ${formatAgo(viewer.lastSeenMs)} · ${viewer.sessions30D} sessions in 30 days",
                        onRevoke = { revoking = viewer },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    revoking?.let { viewer ->
        ConfirmSheet(
            title = "Revoke ${viewer.label}?",
            body = if (viewer.fingerprint == selfFingerprint) {
                "This is the phone you are holding. It loses access at once and has to pair again from the camera's screen."
            } else {
                "It loses access at once, and any session it has open ends. It has to scan a new code to come back."
            },
            confirm = "Revoke",
            onConfirm = { onRevoke(viewer.fingerprint) },
            onDismiss = { revoking = null },
        )
    }
}
