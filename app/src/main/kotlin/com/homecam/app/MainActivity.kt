package com.homecam.app

import android.content.Intent
import android.os.Bundle
import com.homecam.app.viewer.OpenRequest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import com.homecam.app.camera.CameraRoot
import com.homecam.app.camera.CameraService
import com.homecam.app.camera.ScreenDimmer
import com.homecam.app.ui.RolePickerScreen
import com.homecam.app.viewer.ViewerRoot
import com.homecam.app.ui.theme.HomecamTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val roles = RoleStore(this)
        openRequest = requestFrom(intent)
        setContent {
            HomecamTheme {
                // The paper runs under the system bars; only the content keeps clear of them.
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    var role by remember { mutableStateOf(roles.role) }
                    // Leaving the camera role stops its foreground service; both stores
                    // (this phone's identity, its pairings) survive the switch, so a
                    // repurposed phone keeps who it already trusts.
                    val switchRole = {
                        if (role == Role.CAMERA) CameraService.stop(this@MainActivity)
                        roles.role = null
                        role = null
                    }
                    when (role) {
                        null -> RolePickerScreen { picked -> roles.role = picked; role = picked }
                        Role.CAMERA -> CameraRoot(onSwitchRole = switchRole)
                        Role.VIEWER -> ViewerRoot(switchRole, openRequest) { openRequest = null }
                    }
                } }
            }
        }
    }

    private var openRequest by mutableStateOf<OpenRequest?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestFrom(intent)?.let { openRequest = it }
    }

    private fun requestFrom(intent: Intent?): OpenRequest? {
        val camera = intent?.getStringExtra(EXTRA_CAMERA)?.takeIf { it.isNotBlank() } ?: return null
        return OpenRequest(camera, intent.getLongExtra(EXTRA_AT_MS, 0).takeIf { it > 0 })
    }

    override fun onStart() {
        super.onStart()
        ScreenDimmer.register(window)
    }

    override fun onStop() {
        ScreenDimmer.unregister(window)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is a visible window, which is what the camera needs after a
        // background start. Restarting when frames are already flowing would drop them.
        if (RoleStore(this).role == Role.CAMERA && CameraService.isBlind.value) {
            CameraService.resume(this)
        }
    }

    companion object {
        const val EXTRA_CAMERA = "com.homecam.app.CAMERA"
        const val EXTRA_AT_MS = "com.homecam.app.AT_MS"
    }
}
