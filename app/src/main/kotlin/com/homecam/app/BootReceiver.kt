package com.homecam.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.homecam.app.alerts.AlertPreferences
import com.homecam.app.alerts.AlertSubscriber
import com.homecam.app.camera.CameraService

/**
 * Brings the phone's role back up after a reboot or an app update (`PLAN.md` §9.3). A
 * camera taped above a door is expected to run for weeks without anyone opening the app,
 * so a power cut must not be the thing that quietly ends the recording.
 *
 * Newer Android versions refuse some foreground-service starts from `BOOT_COMPLETED`. When
 * that happens there is nothing this receiver can do about it — so it says so, loudly, in
 * the notification shade. A camera that has stopped and told you is recoverable; a camera
 * that has stopped silently is the failure this whole design is against.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        when (RoleStore(context).role) {
            Role.CAMERA -> resume(context, "Camera is not running") {
                CameraService.start(context)
            }

            Role.VIEWER -> if (AlertPreferences.enabled(context)) {
                resume(context, "Alerts are not running") { AlertSubscriber.start(context) }
            }

            null -> Unit
        }
    }

    private fun resume(context: Context, whatStopped: String, start: () -> Unit) {
        try {
            start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "$whatStopped: the system refused the start after boot", e)
            warn(context, whatStopped)
        }
    }

    private fun warn(context: Context, whatStopped: String) {
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

    private companion object {
        const val TAG = "BootReceiver"
        const val CHANNEL = "homecam.stopped"
        const val NOTIFICATION_ID = 3
    }
}
