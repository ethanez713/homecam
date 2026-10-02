package com.homecam.app.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.HardwareProtectionControl
import com.homecam.app.ui.RecordingModeControl
import com.homecam.app.ui.kit.Badge
import com.homecam.app.ui.kit.Chevron
import com.homecam.app.ui.kit.ConfirmSheet
import com.homecam.app.ui.kit.DangerButton
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.Eyebrow
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.ListRow
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.kit.PrimaryButton
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.SecondaryButton
import com.homecam.app.ui.kit.Sheet
import com.homecam.app.ui.kit.SwitchRow
import com.homecam.app.ui.kit.Tag
import com.homecam.app.ui.kit.Tone
import com.homecam.app.ui.theme.Ethan
import com.homecam.core.health.HealthConfig
import com.homecam.proto.HardwareProtection
import com.homecam.proto.RecordingMode
import com.homecam.proto.SettingsSnapshot

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraSettingsScreen(
    settings: SettingsSnapshot?,
    onArmed: (Boolean) -> Unit,
    onAlias: (String) -> Unit,
    onMicrophone: (Boolean) -> Unit,
    onRecordingMode: (RecordingMode) -> Unit,
    projectedDays: Double?,
    onDetectionMode: (person: Boolean, vehicle: Boolean, pet: Boolean, packages: Boolean) -> Unit,
    autoSpotlightSupported: Boolean,
    autoSpotlight: Boolean,
    onAutoSpotlight: (Boolean) -> Unit,
    reminderEnabled: Boolean,
    onDetectionReminder: (Boolean) -> Unit,
    soundDetectionEnabled: Boolean,
    onSoundDetection: (Boolean) -> Unit,
    soundClasses: Set<String>,
    onSoundClass: (String, Boolean) -> Unit,
    alertsEnabled: Boolean,
    onAlerts: (Boolean) -> Unit,
    remoteAddress: String,
    onRemoteAddress: (String) -> Unit,
    onDevices: () -> Unit,
    protection: HealthConfig? = null,
    onProtection: (HardwareProtection) -> Unit = {},
    onLog: () -> Unit = {},
    onForget: () -> Unit = {},
    verificationPhrase: String = "",
    fingerprint: String = "",
) {
    if (settings == null) {
        EmptyState("Reading the camera's settings", busy = true)
        return
    }

    var editing by remember { mutableStateOf<Field?>(null) }
    var forgetting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter)) {
        Eyebrow("Watching")
        PaperCard(padding = 0.dp) {
            SwitchRow(
                "Armed", settings.armed, onArmed,
                meta = if (settings.armed) "Detecting, recording events and alerting" else "Not detecting. Nothing is recorded or alerted",
            )
            Rule()
            SwitchRow("Push alerts to this phone", alertsEnabled, onAlerts, meta = "Encrypted to this phone; the push service sees only ciphertext")
        }

        Eyebrow("Recording")
        PaperCard(padding = 0.dp) { RecordingModeControl(settings.recordingMode, projectedDays, onRecordingMode) }

        if (protection != null) {
            Eyebrow("Protection")
            PaperCard(padding = 0.dp) { HardwareProtectionControl(protection, onProtection) }
        }

        val mode = settings.detectionMode
        Eyebrow("Detect")
        PaperCard(padding = 0.dp) {
            SwitchRow("People", mode.person, { onDetectionMode(it, mode.vehicle, mode.pet, mode.getPackage()) })
            Rule()
            SwitchRow("Vehicles", mode.vehicle, { onDetectionMode(mode.person, it, mode.pet, mode.getPackage()) })
            Rule()
            SwitchRow("Pets", mode.pet, { onDetectionMode(mode.person, mode.vehicle, it, mode.getPackage()) })
            Rule()
            SwitchRow("Packages", mode.getPackage(), { onDetectionMode(mode.person, mode.vehicle, mode.pet, it) })
        }

        Eyebrow("Sound")
        PaperCard(padding = 0.dp) {
            SwitchRow("Microphone", settings.microphone, onMicrophone, meta = "Send the camera's audio and let it listen")
            Rule()
            SwitchRow("Loud sounds", soundDetectionEnabled, onSoundDetection, meta = "Record on a loud noise")
            if (soundDetectionEnabled) {
                Rule()
                Column(Modifier.padding(12.dp)) {
                    Text("Name these when heard", style = Ethan.type.bodySmall, color = Ethan.colors.textMuted)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.homecam.core.sound.SoundClasses.ALL.forEach { soundClass ->
                            Tag(soundClass, selected = soundClass in soundClasses, onClick = { onSoundClass(soundClass, soundClass !in soundClasses) })
                        }
                    }
                }
            }
        }

        Eyebrow("Respond")
        PaperCard(padding = 0.dp) {
            if (autoSpotlightSupported) {
                SwitchRow("Auto spotlight", autoSpotlight, onAutoSpotlight, meta = "Light up when something is seen in the dark")
                Rule()
            }
            SwitchRow("Armed reminder", reminderEnabled, onDetectionReminder, meta = "A notice on the camera every hour while it is armed")
        }

        Eyebrow("Camera")
        PaperCard(padding = 0.dp) {
            ListRow("Name", meta = settings.alias, onClick = { editing = Field.NAME }) { Chevron() }
            Rule()
            ListRow(
                "Remote address",
                meta = remoteAddress.ifBlank { "Not set. Live view works on the home network only" },
                metaMono = remoteAddress.isNotBlank(),
                onClick = { editing = Field.REMOTE },
            ) {
                if (remoteAddress.isBlank()) Badge("Home only", Tone.WARNING)
                Chevron()
            }
            Rule()
            ListRow("Paired devices", meta = "Who can see this camera, and revoke", onClick = onDevices) { Chevron() }
            Rule()
            ListRow("Access log", meta = "Every connection, with a tamper check", onClick = onLog) { Chevron() }
            if (fingerprint.isNotBlank()) {
                Rule()
                ListRow("Fingerprint", meta = fingerprint, metaMono = true)
            }
            if (verificationPhrase.isNotBlank()) {
                Rule()
                ListRow("Verification phrase", meta = verificationPhrase, metaMono = true)
            }
        }
        Spacer(Modifier.height(12.dp))
        DangerButton("Forget this camera", { forgetting = true }, Modifier.fillMaxWidth(), filled = false)
        Spacer(Modifier.height(24.dp))
    }

    if (forgetting) {
        ConfirmSheet(
            title = "Forget ${settings.alias}?",
            body = "This phone stops trusting the camera. To watch it again, pair from the camera's screen.",
            confirm = "Forget",
            onConfirm = onForget,
            onDismiss = { forgetting = false },
        )
    }

    when (editing) {
        Field.NAME -> EditSheet(
            title = "Name this camera",
            hint = "Shown on this phone and in alerts.",
            initial = settings.alias,
            onSave = onAlias,
            onDismiss = { editing = null },
        )
        Field.REMOTE -> RemoteAddressSheet(remoteAddress, onRemoteAddress) { editing = null }
        null -> Unit
    }
}

private enum class Field { NAME, REMOTE }

/** One line of text, asked for from the bottom edge. */
@Composable
fun EditSheet(
    title: String,
    hint: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    allowBlank: Boolean = false,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    Sheet(onDismiss) {
        Text(title, style = Ethan.type.title2, color = Ethan.colors.textHeading)
        Text(hint, style = Ethan.type.bodySmall, color = Ethan.colors.textMuted)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = Ethan.type.body,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ethan.colors.accent,
                unfocusedBorderColor = Ethan.colors.borderStrong,
                focusedContainerColor = Ethan.colors.surfaceCard,
                unfocusedContainerColor = Ethan.colors.surfaceCard,
                cursorColor = Ethan.colors.accent,
            ),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Cancel", onDismiss, Modifier.weight(1f))
            PrimaryButton(
                "Save", { onSave(value.trim()); onDismiss() }, Modifier.weight(1f),
                enabled = value.trim() != initial && (allowBlank || value.isNotBlank()),
            )
        }
    }
}

@Composable
fun RemoteAddressSheet(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    EditSheet(
        title = "Remote address",
        hint = "The camera's Tailscale name or address, as host or host:port. Used when it is not on this network.",
        initial = current,
        onSave = onSave,
        onDismiss = onDismiss,
        allowBlank = true,
    )
}
