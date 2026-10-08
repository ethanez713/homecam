package com.homecam.app

import android.content.Context
import java.io.File
import com.homecam.core.pairing.CameraPairingSession
import java.util.concurrent.atomic.AtomicLong

/**
 * Identities paired through [com.homecam.app.debug.PairingArmReceiver], which only `adb shell`
 * can reach. Their sessions are audited like any other but do not push a "started watching"
 * alert to the real viewer, so a harness run does not spend the viewer's alert budget (D19).
 */
object HarnessIdentities {
    private val armedAtMs = AtomicLong(0)

    fun arm() = armedAtMs.set(System.currentTimeMillis())

    /** Only a pairing inside the window the receiver opened counts, so a later real pairing is never muted. */
    fun onPaired(context: Context, fingerprint: String) {
        val armedAt = armedAtMs.getAndSet(0)
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > CameraPairingSession.DEFAULT_TTL_MS) return
        file(context).appendText("$fingerprint\n")
    }

    fun isHarness(context: Context, fingerprint: String): Boolean =
        file(context).takeIf { it.exists() }?.readLines()?.contains(fingerprint) == true

    private fun file(context: Context) = File(context.filesDir, "harness-identities.txt")
}
