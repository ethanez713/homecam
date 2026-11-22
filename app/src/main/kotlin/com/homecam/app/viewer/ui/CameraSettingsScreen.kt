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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.HardwareProtectionControl
import com.homecam.app.ui.formatStamp
import com.homecam.app.ui.RecordingModeControl
import com.homecam.app.ui.kit.Badge
import com.homecam.app.ui.kit.Segmented
import com.homecam.app.ui.kit.Chevron
import com.homecam.app.ui.kit.ConfirmSheet
import com.homecam.app.ui.kit.DangerButton
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.Eyebrow
import com.homecam.app.ui.kit.Gutter
import com.homecam.app.ui.kit.ListRow
import com.homecam.app.ui.kit.PaperCard
import com.homecam.app.ui.kit.PrimaryButton
import com.homecam.app.ui.kit.QuietButton
import com.homecam.app.ui.kit.Rule
import com.homecam.app.ui.kit.SecondaryButton
import com.homecam.app.ui.kit.Sheet
import com.homecam.app.ui.kit.SwitchRow
import com.homecam.app.ui.kit.Tag
import com.homecam.app.ui.kit.Tone
import com.homecam.app.ui.theme.Ethan
import com.homecam.core.health.HealthConfig
import com.homecam.core.media.CaptureQuality
import com.homecam.core.unlock.UnlockPolicy
import com.homecam.proto.GestureUnlockStatus
import com.homecam.proto.HardwareProtection
import com.homecam.proto.QualityTier
import com.homecam.proto.RecordingMode
import com.homecam.proto.SettingsSnapshot

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraSettingsScreen(
    settings: SettingsSnapshot?,
    onArmed: (Boolean) -> Unit,
    onAlias: (String) -> Unit,
    onMicrophone: (Boolean) -> Unit,
    onAutoRotate: (Boolean) -> Unit,
    onOsd: (Boolean) -> Unit,
    onRecordingMode: (RecordingMode) -> Unit,
    onPrivacyMode: (Boolean) -> Unit = {},
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
    healthAlertsEnabled: Boolean = true,
    onHealthAlerts: (Boolean) -> Unit = {},
    remoteAddress: String,
    onRemoteAddress: (String) -> Unit,
    onDevices: () -> Unit,
    protection: HealthConfig? = null,
    onProtection: (HardwareProtection) -> Unit = {},
    onLog: () -> Unit = {},
    onForget: () -> Unit = {},
    verificationPhrase: String = "",
    fingerprint: String = "",
    qualityTiers: List<QualityTier> = CaptureQuality.profiles.map { it.tier },
    onQuality: (QualityTier) -> Unit = {},
    lowLightSupported: Boolean = false,
    onLowLight: (Boolean) -> Unit = {},
    onZoomInLock: (Boolean) -> Unit = {},
    gestureUnlockSupported: Boolean = false,
    onGestureUnlock: (Boolean, Boolean) -> Unit = { _, _ -> },
    onUnlockCode: (String) -> Unit = { _ -> },
    onAutoPowerSaving: (Boolean) -> Unit = {},
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
            if (alertsEnabled && protection != null) {
                Rule()
                SwitchRow("Health alerts", healthAlertsEnabled, onHealthAlerts, meta = "When the camera pauses recording to protect the phone, and when it resumes")
            }
        }

        Eyebrow("Recording")
        PaperCard(padding = 0.dp) {
            SwitchRow(
                "Privacy mode — camera off",
                settings.privacyMode,
                onPrivacyMode,
                meta = "Stops the camera capturing anything",
            )
            Rule()
            RecordingModeControl(settings.recordingMode, projectedDays, onRecordingMode)
            if (qualityTiers.isNotEmpty()) {
                val selected = settings.qualityTier.takeIf { it in qualityTiers } ?: qualityTiers.first()
                Rule()
                Column(Modifier.padding(12.dp)) {
                    Text("Quality", style = Ethan.type.bodyStrong, color = Ethan.colors.textHeading)
                    Spacer(Modifier.height(8.dp))
                    Segmented(qualityTiers.map { it to qualityLabel(it) }, selected, onQuality)
                    Spacer(Modifier.height(8.dp))
                    Text(qualityMeta(selected), style = Ethan.type.bodySmall, color = Ethan.colors.textMuted)
                }
            }
        }

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
                    Text("Notify for these sounds", style = Ethan.type.bodySmall, color = Ethan.colors.textMuted)
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

        if (gestureUnlockSupported) {
            val unlock = settings.gestureUnlock
            val lockout = lockoutDetail(unlock, System.currentTimeMillis())
            Eyebrow("Door unlock")
            PaperCard(padding = 0.dp) {
                SwitchRow(
                    "Gesture unlock",
                    unlock.enabled,
                    { onGestureUnlock(it, false) },
                    meta = gestureUnlockMeta(unlock),
                    enabled = unlock.codeSet,
                )
                Rule()
                ListRow(
                    "Set code",
                    meta = if (unlock.codeSet) "Replace the six-sign code" else "Six signs, each from 1 to 5",
                    onClick = { editing = Field.CODE },
                ) { Chevron() }
                if (lockout != null) {
                    Rule()
                    ListRow("Lockout", meta = lockout) {
                        QuietButton("Clear", { onGestureUnlock(unlock.enabled, true) })
                    }
                }
            }
        }

        Eyebrow("Camera")
        PaperCard(padding = 0.dp) {
            SwitchRow(
                "Auto-rotate",
                settings.rotation.auto,
                onAutoRotate,
                meta = "Keep the picture upright as the camera turns",
            )
            Rule()
            SwitchRow(
                "Timestamp overlay",
                settings.osd,
                onOsd,
                meta = "Burn the time and camera name into the picture",
            )
            if (lowLightSupported) {
                Rule()
                SwitchRow("Low light", settings.lowLight, onLowLight, meta = "Brighten a dark scene")
            }
            Rule()
            SwitchRow(
                "Zoom-in lock",
                settings.zoomInLock.enabled,
                onZoomInLock,
                meta = "Keep each lens at the zoom it was left on",
            )
            Rule()
            SwitchRow(
                "Auto power saving",
                !settings.hasAutoPowerSaving() || settings.autoPowerSaving.enabled,
                onAutoPowerSaving,
                meta = "Slow the camera when nothing is happening",
            )
            Rule()
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
        Field.CODE -> UnlockCodeSheet(onUnlockCode) { editing = null }
        null -> Unit
    }
}

private fun qualityLabel(tier: QualityTier) = when (tier) {
    QualityTier.QUALITY_MEDIUM -> "Medium"
    QualityTier.QUALITY_LOW -> "Low"
    else -> "High"
}

private fun qualityMeta(tier: QualityTier): String {
    val profile = CaptureQuality.profile(tier)
    val mbps = profile.bitrateBps / 1_000_000.0
    val text = if (profile.bitrateBps % 1_000_000 == 0) "%.0f".format(mbps) else "%.1f".format(mbps)
    return "${profile.height}p · ${profile.fps} fps · $text Mbps"
}

private enum class Field { NAME, REMOTE, CODE }

private fun gestureUnlockMeta(status: GestureUnlockStatus): String = when {
    !status.codeSet -> "Set a code before this can turn on"
    status.enabled -> "A gesture code at the door can unlock it"
    else -> "Off. The code stays set"
}

private fun lockoutDetail(status: GestureUnlockStatus, nowMs: Long): String? {
    val locked = status.lockedOutUntilMs > nowMs
    val failures = status.failures
    if (!locked && failures <= 0) return null
    val until = if (locked) "Locked out until ${formatStamp(status.lockedOutUntilMs)}" else null
    val wrong = when {
        failures <= 0 -> null
        failures == 1 -> "1 wrong code"
        else -> "$failures wrong codes"
    }
    return listOfNotNull(until, wrong).joinToString(" · ")
}

/** Six marks. The count is all this knows, so the code itself cannot land on screen. */
private fun signDots(filled: Int): String = buildString {
    val n = filled.coerceIn(0, UnlockPolicy.CODE_LENGTH)
    repeat(UnlockPolicy.CODE_LENGTH) { index ->
        if (index > 0) append(' ')
        append(if (index < n) '●' else '○')
    }
}

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

/**
 * Two matching entries of six signs. The entry lives in [remember] only: a door code
 * must not be restored from saved state, and both strings are wiped when the sheet closes.
 */
@Composable
private fun UnlockCodeSheet(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }

    fun wipe() {
        first = ""
        second = ""
        confirming = false
        mismatch = false
    }

    fun close() {
        wipe()
        onDismiss()
    }

    fun finish() {
        val code = first
        val ok = second == code && UnlockPolicy.codeOf(code) != null
        wipe()
        if (!ok) {
            mismatch = true
            return
        }
        onSave(code)
        onDismiss()
    }

    fun enter(sign: Char) {
        if (sign !in '1'..'5') return
        mismatch = false
        if (confirming) {
            if (second.length >= UnlockPolicy.CODE_LENGTH) return
            second += sign
            if (second.length == UnlockPolicy.CODE_LENGTH) finish()
        } else {
            if (first.length >= UnlockPolicy.CODE_LENGTH) return
            first += sign
            if (first.length == UnlockPolicy.CODE_LENGTH) confirming = true
        }
    }

    fun backspace() {
        mismatch = false
        if (confirming) {
            if (second.isNotEmpty()) {
                second = second.dropLast(1)
            } else {
                confirming = false
                if (first.isNotEmpty()) first = first.dropLast(1)
            }
        } else if (first.isNotEmpty()) {
            first = first.dropLast(1)
        }
    }

    val filled = if (confirming) second.length else first.length
    Sheet(::close) {
        Text("Set a code", style = Ethan.type.title2, color = Ethan.colors.textHeading)
        Text(
            if (confirming) "Enter the same six signs again."
            else "Enter six signs, then the same six again. Each sign is from 1 to 5.",
            style = Ethan.type.bodySmall,
            color = Ethan.colors.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            signDots(filled),
            style = Ethan.type.monoLarge,
            color = Ethan.colors.textHeading,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "$filled of ${UnlockPolicy.CODE_LENGTH} signs"
            },
        )
        if (mismatch) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Those didn't match. Start again.",
                style = Ethan.type.bodySmall,
                color = Ethan.colors.textDanger,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (sign in 1..5) {
                SecondaryButton(sign.toString(), { enter(sign.digitToChar()) }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Delete", ::backspace, Modifier.weight(1f), enabled = filled > 0 || confirming)
            SecondaryButton("Cancel", ::close, Modifier.weight(1f))
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
