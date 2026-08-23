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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.homecam.core.audit.AuditChain
import com.homecam.app.ui.formatStamp
import com.homecam.proto.AuditEvent

/** The access log (§2.7), including whether its hash chain still verifies. */
@Composable
fun AuditScreen(events: List<AuditEvent>, verification: AuditChain.Verification?) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Access log", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        ChainBanner(verification)
        Spacer(Modifier.height(12.dp))

        LazyColumn { items(events, key = { it.seq }) { EventRow(it) } }
    }
}

@Composable
private fun ChainBanner(verification: AuditChain.Verification?) {
    val (text, container) = when {
        verification == null -> "Checking the chain…" to MaterialTheme.colorScheme.surfaceVariant
        verification.valid -> "Chain intact — no entry has been edited or removed" to
            MaterialTheme.colorScheme.secondaryContainer
        else -> "Chain broken at entry ${verification.firstBrokenSeq} — this log has been tampered with" to
            MaterialTheme.colorScheme.errorContainer
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
