package com.homecam.app.camera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.app.R
import com.homecam.app.data.ViewerEntity
import com.homecam.app.ui.formatAgo
import com.homecam.app.ui.formatStamp
import com.homecam.app.ui.kit.ConfirmSheet
import com.homecam.app.ui.kit.DangerButton
import com.homecam.app.ui.kit.DeviceRow
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.Eyebrow
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.TopBar

/**
 * The roster from §2.6. Everything here is one screen deep from the home screen because
 * a control nobody can find is not a control.
 */
@Composable
fun RosterScreen(
    viewers: List<ViewerEntity>,
    sessionsIn30d: suspend (String) -> Int,
    onRevoke: (String) -> Unit,
    onRevokeAll: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmRevokeAll by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<ViewerEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Paired devices", onBack = onBack)
        if (viewers.isEmpty()) {
            EmptyState(
                "Nobody can see this camera.",
                detail = "Pair a device from the home screen to let one in.",
                icon = R.drawable.lucide_users,
            )
            return@Column
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Gutter)) {
            Eyebrow("${viewers.size} ${if (viewers.size == 1) "device has" else "devices have"} access")
            PaperCard(padding = 0.dp) {
                viewers.forEachIndexed { index, viewer ->
                    if (index > 0) Rule()
                    val sessions by produceState(initialValue = -1, viewer.fingerprint) {
                        value = sessionsIn30d(viewer.fingerprint)
                    }
                    DeviceRow(
                        label = viewer.label,
                        self = false,
                        fingerprint = viewer.fingerprint,
                        detail = "Paired ${formatStamp(viewer.pairedAtMs)} · seen ${formatAgo(viewer.lastSeenMs)} · " +
                            "${if (sessions < 0) "…" else sessions.toString()} sessions in 30 days",
                        onRevoke = { revoking = viewer },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            DangerButton("Revoke all", { confirmRevokeAll = true }, Modifier.fillMaxWidth(), filled = false)
            Spacer(Modifier.height(24.dp))
        }
    }

    revoking?.let { viewer ->
        ConfirmSheet(
            title = "Revoke ${viewer.label}?",
            body = "It loses access at once, and any session it has open ends. It has to scan a new code to come back.",
            confirm = "Revoke",
            onConfirm = { onRevoke(viewer.fingerprint) },
            onDismiss = { revoking = null },
        )
    }
    if (confirmRevokeAll) {
        ConfirmSheet(
            title = "Revoke every device?",
            body = "This camera goes back to unpaired. Any live session ends at once and every device has to scan a new code.",
            confirm = "Revoke all",
            onConfirm = onRevokeAll,
            onDismiss = { confirmRevokeAll = false },
        )
    }
}
