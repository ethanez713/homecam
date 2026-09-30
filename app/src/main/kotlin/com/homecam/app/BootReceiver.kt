package com.homecam.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.homecam.app.alerts.AlertPreferences
import com.homecam.app.alerts.AlertSubscriber
import com.homecam.app.camera.CameraService
import com.homecam.app.camera.ResumeCameraActivity

/**
 * Brings the phone's role back up after a reboot or an app update (`PLAN.md` §9.3). A
 * camera taped above a door is expected to run for weeks without anyone opening the app,
 * so a power cut must not be the thing that quietly ends the recording.
 *
 * From Android 15 a camera foreground service cannot be started from `BOOT_COMPLETED` at
 * all, and even where the start succeeds the camera stays denied until something is on
 * screen. Display-over-other-apps lets the resume activity be that something. Otherwise
 * the start is attempted and, if the system refuses, said so in the shade — a camera
 * that has stopped and told you is recoverable; one that stopped silently is not.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        when (RoleStore(context).role) {
            Role.CAMERA -> if (Settings.canDrawOverlays(context)) {
                CameraService.startReason = "boot"
                launchResume(context)
            } else {
                resume(context, "Camera is not running") {
                    CameraService.startReason = "boot"
                    CameraService.start(context)
                }
            }

            Role.VIEWER -> if (AlertPreferences.enabled(context)) {
                resume(context, "Alerts are not running") { AlertSubscriber.start(context) }
            }

            null -> Unit
        }
    }

    private fun launchResume(context: Context) {
        runCatching { context.startActivity(ResumeCameraActivity.intent(context)) }
            .onFailure {
                Log.w(TAG, "Camera is not running: could not open the resume activity", it)
                warnStopped(context, "Camera is not running")
            }
    }

    private fun resume(context: Context, whatStopped: String, start: () -> Unit) {
        try {
            start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "$whatStopped: the system refused the start after boot", e)
            warnStopped(context, whatStopped)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val CHANNEL = "homecam.stopped"
        private const val NOTIFICATION_ID = 3

        internal fun warnStopped(context: Context, whatStopped: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Stopped", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(context)
            }
            manager.notify(
                NOTIFICATION_ID,
                builder.setContentTitle(whatStopped)
                    .setContentText("Android would not start it after the reboot. Tap to resume.")
                    .setSmallIcon(R.drawable.ic_stat_camera)
                    .setContentIntent(open)
                    .setOngoing(true)
                    .build(),
            )
        }
    }
}
