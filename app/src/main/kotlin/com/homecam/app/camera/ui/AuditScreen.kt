package com.homecam.app.camera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.homecam.core.audit.AuditChain
import com.homecam.app.ui.formatStamp
import com.homecam.proto.AuditEvent

/** The access log (§2.7), including whether its hash chain still verifies. */
@Composable
fun AuditScreen(events: List<AuditEvent>, verification: AuditChain.Verification?) {
    var filter by remember { mutableStateOf(AuditFilter.ALL) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Access log", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        ChainBanner(verification)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuditFilter.entries.forEach { entry ->
                FilterChip(
                    selected = filter == entry,
                    onClick = { filter = entry },
                    label = { Text(entry.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val shown = events.filter { filter.matches(it.kind) }
        if (shown.isEmpty()) {
            Text("Nothing here yet.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn { items(shown, key = { it.seq }) { EventRow(it) } }
    }
}

/**
 * "Who looked" is the question §2.6 says this log exists to answer, and it is easy to lose
 * in a stream of settings changes and detections.
 */
private enum class AuditFilter(val label: String) {
    ALL("All"),
    ACCESS("Access"),
    REFUSED("Refused");

    fun matches(kind: AuditEvent.Kind): Boolean = when (this) {
        ALL -> true
        ACCESS -> kind in setOf(
            AuditEvent.Kind.SESSION_OPENED,
            AuditEvent.Kind.SESSION_CLOSED,
            AuditEvent.Kind.CLIP_VIEWED,
            AuditEvent.Kind.PAIRING_COMPLETED,
            AuditEvent.Kind.VIEWER_REVOKED,
        )
        REFUSED -> kind in setOf(
            AuditEvent.Kind.CONNECTION_REJECTED,
            AuditEvent.Kind.FINGERPRINT_MISMATCH,
            AuditEvent.Kind.PAIRING_EXPIRED,
        )
    }
}

@Composable
private fun ChainBanner(verification: AuditChain.Verification?) {
    val (text, container) = when {
        verification == null -> "Checking the chain…" to MaterialTheme.colorScheme.surfaceVariant
        verification.valid -> "Chain intact — no entry has been edited or removed" to
            MaterialTheme.colorScheme.secondaryContainer
        else -> "Chain broken at entry ${verification.firstBrokenSeq} — an entry has been " +
            "edited or removed since it was written" to MaterialTheme.colorScheme.errorContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EventRow(event: AuditEvent) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(describe(event.kind), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(formatStamp(event.atMs), style = MaterialTheme.typography.bodySmall)
        }
        val who = listOfNotNull(
            event.viewerLabel.takeIf { it.isNotBlank() },
            event.viewerFingerprint.takeIf { it.isNotBlank() },
            event.sourceIp.takeIf { it.isNotBlank() },
            event.detail.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (who.isNotBlank()) {
            Text(
                who,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

private fun describe(kind: AuditEvent.Kind): String = when (kind) {
    AuditEvent.Kind.SESSION_OPENED -> "Session opened"
    AuditEvent.Kind.SESSION_CLOSED -> "Session closed"
    AuditEvent.Kind.CLIP_VIEWED -> "Clip viewed"
    AuditEvent.Kind.CLIP_DELETED -> "Clip deleted"
    AuditEvent.Kind.SETTINGS_CHANGED -> "Settings changed"
    AuditEvent.Kind.PRIVACY_MASK_EDITED -> "Privacy mask edited"
    AuditEvent.Kind.PAIRING_STARTED -> "Pairing started"
    AuditEvent.Kind.PAIRING_COMPLETED -> "Device paired"
    AuditEvent.Kind.PAIRING_EXPIRED -> "Pairing window closed"
    AuditEvent.Kind.VIEWER_REVOKED -> "Device revoked"
    AuditEvent.Kind.CONNECTION_REJECTED -> "Connection refused"
    AuditEvent.Kind.FINGERPRINT_MISMATCH -> "Media fingerprint mismatch"
    AuditEvent.Kind.CAMERA_STARTED -> "Camera started"
    AuditEvent.Kind.CAMERA_STOPPED -> "Camera stopped"
    AuditEvent.Kind.DETECTION -> "Detection"
    else -> "Event"
}
