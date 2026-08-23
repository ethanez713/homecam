package com.homecam.app.alerts

import android.content.Context

/** Alerts are off by default and stay off until the user turns them on (§2.9). */
object AlertPreferences {

    private const val PREFS = "homecam.alerts"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
