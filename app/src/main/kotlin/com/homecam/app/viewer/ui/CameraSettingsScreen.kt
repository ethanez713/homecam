package com.homecam.app.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homecam.proto.SettingsSnapshot

@Composable
fun CameraSettingsScreen(
    settings: SettingsSnapshot?,
    onArmed: (Boolean) -> Unit,
    onAlias: (String) -> Unit,
    onMicrophone: (Boolean) -> Unit,
    onDetectionMode: (person: Boolean, vehicle: Boolean, pet: Boolean, packages: Boolean) -> Unit,
    alertsEnabled: Boolean,
    onAlerts: (Boolean) -> Unit,
    remoteAddress: String,
    onRemoteAddress: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (settings == null) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
        return
    }

    var alias by remember(settings.alias) { mutableStateOf(settings.alias) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Camera settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = alias,
            onValueChange = { alias = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onAlias(alias) }, enabled = alias != settings.alias) {
                Text("Rename")
            }
        }

        Spacer(Modifier.height(8.dp))
        Toggle("Armed", "Record when something is detected", settings.armed, onArmed)
        Toggle("Microphone", "Send the camera's audio", settings.microphone, onMicrophone)

        Spacer(Modifier.height(16.dp))
        Text("Detect", style = MaterialTheme.typography.titleMedium)
        val mode = settings.detectionMode
        Toggle("People", null, mode.person) {
            onDetectionMode(it, mode.vehicle, mode.pet, mode.getPackage())
        }
        Toggle("Vehicles", null, mode.vehicle) {
            onDetectionMode(mode.person, it, mode.pet, mode.getPackage())
        }
        Toggle("Pets", null, mode.pet) {
            onDetectionMode(mode.person, mode.vehicle, it, mode.getPackage())
        }
        Toggle("Packages", null, mode.getPackage()) {
            onDetectionMode(mode.person, mode.vehicle, mode.pet, it)
        }

        Spacer(Modifier.height(16.dp))
        Text("Alerts", style = MaterialTheme.typography.titleMedium)
        Toggle(
            "Push alerts",
            "The camera encrypts each alert to this phone and posts only ciphertext. " +
                "The push provider sees opaque bytes and a random topic.",
            alertsEnabled,
            onAlerts,
        )

        Spacer(Modifier.height(16.dp))
        Text("Remote access", style = MaterialTheme.typography.titleMedium)
        var remote by remember(remoteAddress) { mutableStateOf(remoteAddress) }
        OutlinedTextField(
            value = remote,
            onValueChange = { remote = it },
            label = { Text("host or host:port") },
            supportingText = { Text("Used when the camera is not on this network. A Tailscale name works here; mDNS does not cross a tailnet.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onRemoteAddress(remote) }, enabled = remote != remoteAddress) {
                Text("Save")
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun Toggle(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                body?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
