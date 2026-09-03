package com.homecam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.homecam.app.camera.CameraRoot
import com.homecam.app.camera.CameraService
import com.homecam.app.ui.RolePickerScreen
import com.homecam.app.viewer.ViewerRoot
import com.homecam.app.ui.theme.HomecamTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val roles = RoleStore(this)
        setContent {
            HomecamTheme {
                Surface(Modifier.fillMaxSize()) {
                    var role by remember { mutableStateOf(roles.role) }
                    // Leaving the camera role stops its foreground service; both stores
                    // (this phone's identity, its pairings) survive the switch, so a
                    // repurposed phone keeps who it already trusts.
                    val switchRole = {
                        if (role == Role.CAMERA) CameraService.stop(this)
                        roles.role = null
                        role = null
                    }
                    when (role) {
                        null -> RolePickerScreen { picked -> roles.role = picked; role = picked }
                        Role.CAMERA -> CameraRoot(onSwitchRole = switchRole)
                        Role.VIEWER -> ViewerRoot(onSwitchRole = switchRole)
                    }
                }
            }
        }
    }
}
