package com.homecam.app.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.homecam.app.R
import com.homecam.app.ui.kit.EmptyState
import com.homecam.app.ui.kit.PrimaryButton

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

    EmptyState(
        line = "This phone needs a few permissions",
        detail = rationale,
        icon = R.drawable.lucide_lock,
    ) { PrimaryButton("Grant", { ask.launch(missing.toTypedArray()) }) }
}

private fun Context.missing(permissions: List<String>) = permissions.filter {
    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
}
