package com.homecam.app.camera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * A window whose only job is to be visible. A camera foreground service started from
 * the background keeps running, but Android will not let it open the camera until
 * something is on screen — and display-over-other-apps is what lets this open from
 * that background. The permission does not itself grant the camera.
 */
class ResumeCameraActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    override fun onResume() {
        super.onResume()
        // Finished from the callback, after the service has entered the foreground:
        // that call is what re-grants the camera, and this window has to still be up.
        CameraService.resume(this) { finish() }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ResumeCameraActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
    }
}
