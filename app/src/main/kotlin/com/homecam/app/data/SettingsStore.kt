package com.homecam.app.data

import android.content.Context
import com.homecam.core.media.AutoRotation
import com.homecam.proto.RetentionPolicy
import com.homecam.proto.Rotation
import com.homecam.proto.SettingsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Camera settings, held as the same [SettingsSnapshot] that goes over the wire so there
 * is one shape rather than a persistence model plus a transport model plus a mapper.
 */
class SettingsStore(private val context: Context) {

    private val file = File(context.filesDir, "settings.pb")
    private val mutex = Mutex()
    private val state = MutableStateFlow(read())

    val settings: StateFlow<SettingsSnapshot> = state.asStateFlow()

    val current: SettingsSnapshot get() = state.value

    suspend fun update(edit: SettingsSnapshot.Builder.() -> Unit): SettingsSnapshot = mutex.withLock {
        val next = state.value.toBuilder().apply(edit).build()
        file.writeBytes(next.toByteArray())
        state.value = next
        next
    }

    private fun read(): SettingsSnapshot {
        val stored = runCatching { SettingsSnapshot.parseFrom(file.readBytes()) }.getOrNull()
        if (stored != null && stored.hasRotation()) {
            clearLegacyOrientation()
            return stored
        }
        // A quarter someone already set by hand, from before rotation lived in settings.
        val degrees = legacyDegrees()
        val rotation = if (degrees != 0) {
            Rotation.newBuilder().setAuto(false).setDegrees(AutoRotation.quarter(degrees)).build()
        } else {
            Rotation.newBuilder().setAuto(true).build()
        }
        val next = (stored?.toBuilder() ?: fresh()).setRotation(rotation).build()
        if (stored != null || degrees != 0) file.writeBytes(next.toByteArray())
        clearLegacyOrientation()
        return next
    }

    private fun fresh(): SettingsSnapshot.Builder = SettingsSnapshot.newBuilder()
        .setArmed(true)
        .setAlias("Camera")
        .setMicrophone(true)
        .setBoundingBoxOverlay(true)
        .setDetectionMode(
            com.homecam.proto.DetectionMode.newBuilder()
                .setPerson(true).setVehicle(true).setPet(true).setPackage(true)
                .setMinConfidence(0.5f)
        )
        .setRetention(
            RetentionPolicy.newBuilder()
                .setMaxAgeDays(14)
                .setMaxSizeGb(32)
                .setMinFreeSpaceGb(8)
        )

    private fun legacyDegrees(): Int = capturePrefs().getInt(KEY_ORIENTATION, 0)

    private fun clearLegacyOrientation() {
        val prefs = capturePrefs()
        if (!prefs.contains(KEY_ORIENTATION)) return
        prefs.edit().remove(KEY_ORIENTATION).commit()
    }

    private fun capturePrefs() = context.getSharedPreferences(CAPTURE_PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val CAPTURE_PREFS = "homecam.capture"
        const val KEY_ORIENTATION = "orientation_degrees"
    }
}
