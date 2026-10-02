package com.homecam.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.kit.Segmented
import com.homecam.app.ui.theme.Ethan
import com.homecam.proto.RecordingMode

/**
 * Recording mode plus its honest cost. The projection sits beside the control because that
 * is the number that changes by an order of magnitude between detection and continuous — a
 * mode chosen without seeing how far back the camera can then remember is chosen blind (§5).
 * Shared so the camera and the viewer offer exactly the same choice.
 */
@Composable
fun RecordingModeControl(
    mode: RecordingMode,
    projectedDays: Double?,
    onChange: (RecordingMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(12.dp)) {
        Segmented(options, mode, onChange)
        Spacer(Modifier.height(8.dp))
        Text(describe(mode), style = Ethan.type.bodySmall, color = Ethan.colors.textMuted)
        projectedDays?.let {
            Text(
                "≈ %.0f days of history at this rate".format(it),
                style = Ethan.type.monoSmall,
                color = Ethan.colors.textMuted,
            )
        }
    }
}

private val options = listOf(
    RecordingMode.OFF to "Off",
    RecordingMode.DETECTION to "Detection",
    RecordingMode.CONTINUOUS to "Continuous",
)

private fun describe(mode: RecordingMode): String = when (mode) {
    RecordingMode.OFF -> "Keeps nothing. Live view and alerts still work."
    RecordingMode.CONTINUOUS -> "Records without gaps, in two-minute segments."
    else -> "Keeps a clip around each thing it detects."
}
