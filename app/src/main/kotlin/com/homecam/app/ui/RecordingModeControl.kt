package com.homecam.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
) {
    Column {
        Text("Recording", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (option, label) ->
                if (option == mode) {
                    FilledTonalButton(onClick = { onChange(option) }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onChange(option) }) { Text(label) }
                }
            }
        }
        Text(
            describe(mode),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        projectedDays?.let {
            Text(
                "≈ %.0f days of history at the current rate".format(it),
                style = MaterialTheme.typography.bodySmall,
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
    RecordingMode.CONTINUOUS -> "Gap-free recording, segmented so a crash costs one segment."
    else -> "Keeps clips around what is detected."
}
