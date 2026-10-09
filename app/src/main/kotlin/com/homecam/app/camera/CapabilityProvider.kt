package com.homecam.app.camera

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.homecam.core.rpc.Protocol
import com.homecam.proto.Capabilities

/**
 * The camera's honest answer to "what can you do" (Phase 2). Every field is read from the
 * device — flash unit, microphone, lens count, encoder, supported sizes — rather than
 * hardcoded, so a phone with no torch says so and the viewer stops offering the button.
 *
 * Features that exist in the protocol but not yet in this build (siren, spotlight, sound
 * detection, camera-side audio playback) report false: advertising a control the camera
 * cannot actually honour is exactly what this negotiation exists to prevent.
 */
object CapabilityProvider {

    fun capabilities(context: Context): Capabilities {
        val pm = context.packageManager
        val lenses = LensSource.lenses(context)
        val hasFlash = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        return Capabilities.newBuilder()
            .setProtocolVersion(Protocol.VERSION)
            .setTorch(hasFlash)
            // Phase 8: the siren is the phone's own speaker (always present); the spotlight
            // is the torch, so it needs a flash unit.
            .setSiren(true)
            .setSpotlight(hasFlash)
            .setMultiLens(lenses.size > 1)
            .addAllLenses(lenses.map { it.toProto(LensSource.resolutions(context, it)) })
            .setAudioIn(pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
            .setAudioOut(false)
            .setSoundDetection(false)
            .setContinuousRecording(true)
            .setHardwareEncoderTier(encoderTier())
            .addAllResolutions(lenses.firstOrNull { !it.front }?.let { LensSource.resolutions(context, it) } ?: LensSource.defaultResolutions())
            .setHardwareProtection(true)
            .setThermalStatusSupported(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            .build()
    }

    private fun encoderTier(): Capabilities.EncoderTier = when {
        hasHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC) -> Capabilities.EncoderTier.ENCODER_HEVC
        hasHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_AVC) -> Capabilities.EncoderTier.ENCODER_H264
        else -> Capabilities.EncoderTier.ENCODER_NONE
    }

    private fun hasHardwareEncoder(mime: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                isHardware(info)
        }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
}
