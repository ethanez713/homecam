package com.homecam.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.Segmented
import com.homecam.app.ui.kit.SwitchRow
import com.homecam.app.ui.theme.Ethan
import com.homecam.core.health.HealthConfig
import com.homecam.core.health.HealthProtocol
import com.homecam.proto.HardwareProtection

/**
 * What the camera may do to itself when the phone runs hot or flat. Shared so the camera's
 * own screen and the viewer offer the same choice, as [RecordingModeControl] does.
 */
@Composable
fun HardwareProtectionControl(
    protection: HealthConfig,
    onChange: (HardwareProtection) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun change(edit: (HealthConfig) -> HealthConfig) = onChange(HealthProtocol.protectionOf(edit(protection)))

    Column(modifier) {
        SwitchRow(
            "Pause continuous when hot", protection.thermalAutoPause,
            { on -> change { it.copy(thermalAutoPause = on) } },
            meta = "Falls back to recording detections, and resumes by itself once the phone cools",
        )
        Rule()
        SwitchRow(
            "Pause continuous when the battery runs down", protection.batteryAutoPause,
            { on -> change { it.copy(batteryAutoPause = on) } },
            meta = "Also when a charger cannot keep up",
        )
        if (protection.batteryAutoPause) {
            Column(Modifier.padding(12.dp)) {
                Text("Pause below", style = Ethan.type.bodySmall, color = Ethan.colors.textMuted)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    THRESHOLDS.map { it to "$it%" },
                    THRESHOLDS.minBy { kotlin.math.abs(it - protection.batteryPausePercent) },
                    onSelect = { percent -> change { it.copy(batteryPausePercent = percent) } },
                )
            }
        }
    }
}

private val THRESHOLDS = listOf(20, 30, 40, 50)
