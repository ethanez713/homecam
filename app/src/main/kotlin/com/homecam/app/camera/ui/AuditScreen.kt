package com.homecam.app.camera.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.homecam.app.ui.kit.Badge
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.Gutter
import androidx.compose.foundation.clickable
import com.homecam.app.ui.kit.ListRow
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.Sheet
import com.homecam.app.ui.kit.Tag
import com.homecam.app.ui.kit.Tone
import com.homecam.app.ui.kit.TopBar
import com.homecam.app.ui.theme.Ethan
import com.homecam.core.audit.AuditChain
import com.homecam.proto.AuditEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The access log (§2.7), including whether its hash chain still verifies. [onBack] is given
 * where the log is a screen of its own; inside a camera's tabs the shell already has a bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AuditScreen(events: List<AuditEvent>, verification: AuditChain.Verification?, onBack: (() -> Unit)? = null) {
    var filter by remember { mutableStateOf(AuditFilter.ALL) }
    var opened by remember { mutableStateOf<AuditEvent?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) TopBar("Access log", onBack = onBack)
        Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
            Spacer(Modifier.height(10.dp))
            ChainBanner(verification)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AuditFilter.entries.forEach { entry ->
                    Tag(entry.label, selected = filter == entry, onClick = { filter = entry })
                }
            }

            val shown = events.filter { filter.matches(it.kind) }
            if (shown.isEmpty()) {
                EmptyState("Nothing here yet.", icon = R.drawable.lucide_scroll_text, modifier = Modifier.height(220.dp))
            }
            LazyColumn {
                shown.groupBy { dayOf(it.atMs) }.forEach { (day, thatDay) ->
                    stickyHeader(key = "day-$day") { DayHeader(day, thatDay.size) }
                    items(thatDay, key = { it.seq }) { event ->
                        EventRow(event) { opened = event }
                        Rule()
                    }
                }
            }
        }
    }

    opened?.let { event ->
        Sheet(onDismiss = { opened = null }) {
            Text(describe(event), style = Ethan.type.title2, color = Ethan.colors.textHeading)
            Spacer(Modifier.height(8.dp))
            listOf(
                "When" to stamp.format(Instant.ofEpochMilli(event.atMs).atZone(ZoneId.systemDefault())),
                "Device" to event.viewerLabel,
                "Fingerprint" to event.viewerFingerprint,
                "From" to event.sourceIp,
                "Detail" to event.detail,
                "Entry" to "${event.seq}",
            ).filter { it.second.isNotBlank() }.forEachIndexed { index, (name, value) ->
                if (index > 0) Rule()
                ListRow(name, meta = value, metaMono = true)
            }
        }
    }
}

private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun dayOf(atMs: Long): LocalDate = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate()

private val dayName = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun DayHeader(day: LocalDate, count: Int) {
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth().background(Ethan.colors.bgApp).padding(top = 12.dp, bottom = 6.dp)) {
        Text(
            when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> dayName.format(day)
            }.uppercase(),
            style = Ethan.type.micro, color = Ethan.colors.textFaint, modifier = Modifier.weight(1f),
        )
        Text("$count", style = Ethan.type.micro, color = Ethan.colors.textFaint)
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            verification == null -> Badge("Checking the chain", Tone.NEUTRAL, dot = true)
            verification.valid -> Badge("Chain intact", Tone.SUCCESS, dot = true)
            else -> Badge("Chain broken at entry ${verification.firstBrokenSeq}", Tone.DANGER, dot = true)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                verification == null -> ""
                verification.valid -> "No entry has been edited or removed."
                else -> "An entry was edited or removed after it was written."
            },
            style = Ethan.type.bodySmall,
            color = if (verification?.valid == false) Ethan.colors.textDanger else Ethan.colors.textMuted,
        )
    }
}

@Composable
private fun EventRow(event: AuditEvent, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(describe(event), style = Ethan.type.bodyStrong, color = Ethan.colors.textHeading, modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                clock.format(Instant.ofEpochMilli(event.atMs).atZone(ZoneId.systemDefault())),
                style = Ethan.type.monoSmall, color = Ethan.colors.textFaint,
            )
        }
        // The first block of the fingerprint tells two phones of the same model apart; the
        // whole of it is on the paired-devices screen.
        val device = when {
            event.viewerLabel.isNotBlank() && event.viewerFingerprint.isNotBlank() ->
                "${event.viewerLabel} (${event.viewerFingerprint.substringBefore('-')})"
            else -> event.viewerLabel.ifBlank { event.viewerFingerprint }
        }
        val who = listOfNotNull(
            device.takeIf { it.isNotBlank() },
            event.sourceIp.takeIf { it.isNotBlank() },
            event.detail.takeIf { it.isNotBlank() && it != LIVE_VIDEO },
        ).joinToString(" · ")
        if (who.isNotBlank()) {
            Text(who, style = Ethan.type.monoSmall, color = Ethan.colors.textMuted)
        }
    }
}

private const val LIVE_VIDEO = "live video"

/**
 * A viewer connecting and a viewer starting live video are two entries of the same kind,
 * told apart only by their detail; named separately they stop reading as a duplicate.
 */
private fun describe(event: AuditEvent): String = when {
    event.kind == AuditEvent.Kind.SESSION_OPENED && event.detail == LIVE_VIDEO -> "Live view started"
    event.kind == AuditEvent.Kind.SESSION_OPENED -> "Connected"
    event.kind == AuditEvent.Kind.SESSION_CLOSED && event.detail.contains("·") -> "Live view ended"
    event.kind == AuditEvent.Kind.SESSION_CLOSED -> "Disconnected"
    else -> describe(event.kind)
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
