package com.homecam.app.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.homecam.app.AppGraph
import com.homecam.app.HarnessIdentities
import com.homecam.app.Role
import com.homecam.app.RoleStore
import com.homecam.app.net.Signaling
import java.io.File

/**
 * Arms the camera's pairing window and writes the invite for the GATE-SEC harness.
 *
 * `adb shell am broadcast -n com.homecam.app/.debug.PairingArmReceiver -a com.homecam.app.debug.ARM_PAIRING`
 *
 * The invite file has no trailing newline. Read it with
 * `adb exec-out run-as com.homecam.app cat files/gate-sec-invite.txt`.
 * The camera process has to already be running: the window lives in that process.
 *
 * Debug source set only. After `./gradlew assembleRelease` both of these print nothing
 * (the second needs `$ANDROID_HOME/build-tools/<version>/aapt2`):
 * `grep -RIn -E 'PairingArmReceiver|ARM_PAIRING' app/build/intermediates/merged_manifest/release app/build/intermediates/merged_manifests/release app/build/intermediates/packaged_manifests/release`
 * `aapt2 dump xmltree app/build/outputs/apk/release/app-release-unsigned.apk --file AndroidManifest.xml | grep -E 'PairingArmReceiver|ARM_PAIRING'`
 */
class PairingArmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // A broadcast aimed at the viewer phone must not open a pairing window there.
        if (RoleStore(context).role != Role.CAMERA) {
            Log.w(TAG, "pairing arm ignored: this phone is not the camera")
            resultCode = Activity.RESULT_CANCELED
            resultData = "not-camera"
            return
        }

        val session = AppGraph.get(context).cameraNode.startPairing(Signaling.DEFAULT_PORT)
        val file = File(context.filesDir, INVITE_FILE)
        if (session == null) {
            file.delete()
            Log.w(TAG, "pairing arm failed: no usable address")
            resultCode = Activity.RESULT_CANCELED
            resultData = "no-address"
            return
        }

        HarnessIdentities.arm()
        val invite = session.invite()
        val tmp = File(context.filesDir, "$INVITE_FILE.tmp")
        tmp.writeText(session.inviteText())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            Log.w(TAG, "pairing arm failed: could not write the invite")
            resultCode = Activity.RESULT_CANCELED
            resultData = "write-failed"
            return
        }
        Log.i(TAG, "pairing armed for ${invite.host}:${invite.port}")
        resultCode = Activity.RESULT_OK
        resultData = "armed"
    }

    companion object {
        const val ACTION = "com.homecam.app.debug.ARM_PAIRING"
        const val INVITE_FILE = "gate-sec-invite.txt"
        private const val TAG = "PairingArmReceiver"
    }
}
