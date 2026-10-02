package com.homecam.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.kit.Chevron
import com.homecam.app.ui.kit.ListRow
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.kit.Sheet
import com.homecam.app.ui.kit.Tag
import com.homecam.app.ui.theme.Ethan
import com.homecam.core.health.RecordingReadout
import com.homecam.proto.RecordingMode

data class RecordingPanelState(
    val mode: RecordingMode,
    val readout: RecordingReadout,
    val projectedDays: Double?,
    /** [minutes] of zero means until someone changes it. */
    val onChange: (RecordingMode, minutes: Int) -> Unit,
)

/**
 * What the camera is recording right now, with the control one tap away and no video needed.
 * The line under the title is what is in force, not just what was asked for: a camera that
 * paused itself says so here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingPanel(state: RecordingPanelState, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }

    PaperCard(modifier, padding = 0.dp) {
        ListRow(
            "Recording: ${state.readout.title}",
            meta = state.readout.details.joinToString(" ").ifBlank { null },
            onClick = { open = true },
        ) { Chevron() }
    }

    if (open) {
        Sheet(onDismiss = { open = false }) {
            var choosingDuration by remember { mutableStateOf(false) }
            Text("Recording", style = Ethan.type.title2, color = Ethan.colors.textHeading)
            RecordingModeControl(
                mode = state.mode,
                projectedDays = state.projectedDays,
                onChange = { mode ->
                    if (mode == RecordingMode.CONTINUOUS) choosingDuration = true
                    else { state.onChange(mode, 0); open = false }
                },
            )
            if (choosingDuration || state.mode == RecordingMode.CONTINUOUS) {
                Text("Continuous for", style = Ethan.type.bodySmall, color = Ethan.colors.textMuted, modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DURATIONS.forEach { (minutes, label) ->
                        Tag(label, selected = false, onClick = { state.onChange(RecordingMode.CONTINUOUS, minutes); open = false })
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private val DURATIONS = listOf(60 to "1 hour", 240 to "4 hours", 0 to "Until I change it")
