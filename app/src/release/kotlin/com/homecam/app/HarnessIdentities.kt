package com.homecam.app

import android.content.Context

/** Release builds have no harness identities; the debug source set holds the real one. */
object HarnessIdentities {
    @Suppress("UNUSED_PARAMETER")
    fun onPaired(context: Context, fingerprint: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun isHarness(context: Context, fingerprint: String): Boolean = false
}
