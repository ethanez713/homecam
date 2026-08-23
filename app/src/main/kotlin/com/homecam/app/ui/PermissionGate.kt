package com.homecam.app.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Asks once, explains why, and does not pretend the app works without the answer. The
 * camera role in particular cannot run as a `camera`-type foreground service — the only
 * type without a daily time cap — until the camera permission is actually granted.
 */
@Composable
fun PermissionGate(
    permissions: List<String>,
    rationale: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(context.missing(permissions)) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = context.missing(permissions)
    }
    LaunchedEffect(Unit) { if (missing.isNotEmpty()) ask.launch(missing.toTypedArray()) }

    if (missing.isEmpty()) {
        content()
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("One more thing", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(rationale, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { ask.launch(missing.toTypedArray()) }) { Text("Grant") }
    }
}

private fun Context.missing(permissions: List<String>) = permissions.filter {
    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
}
