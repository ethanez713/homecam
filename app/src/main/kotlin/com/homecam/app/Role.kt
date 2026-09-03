package com.homecam.app

import android.content.Context

/**
 * One APK carries both roles; which one this install is gets chosen at first launch. It
 * usually stays put — an old phone taped above the door rarely changes its mind — but it
 * is changeable from either home screen, so a phone can be repurposed without a reinstall.
 */
enum class Role { CAMERA, VIEWER }

class RoleStore(context: Context) {

    private val prefs = context.getSharedPreferences("homecam.role", Context.MODE_PRIVATE)

    var role: Role?
        get() = prefs.getString(KEY, null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY) else putString(KEY, value.name)
        }.apply()

    private companion object { const val KEY = "role" }
}
