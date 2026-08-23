package com.homecam.app.data

import android.content.Context
import com.homecam.proto.RetentionPolicy
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
class SettingsStore(context: Context) {

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

    private fun read(): SettingsSnapshot = runCatching {
        SettingsSnapshot.parseFrom(file.readBytes())
    }.getOrElse { defaults() }

    private fun defaults(): SettingsSnapshot = SettingsSnapshot.newBuilder()
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
        .build()
}
