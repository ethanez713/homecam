package com.homecam.app.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Size
import com.homecam.core.rpc.Protocol
import com.homecam.proto.Capabilities
import com.homecam.proto.Resolution

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

    private val TIERS = listOf(
        Triple(1920, 1080, 30),
        Triple(1280, 720, 30),
        Triple(640, 480, 30),
    )

    fun capabilities(context: Context): Capabilities {
        val pm = context.packageManager
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val facings = lensFacings(cameraManager)
        return Capabilities.newBuilder()
            .setProtocolVersion(Protocol.VERSION)
            .setTorch(pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
            .setSiren(false)
            .setSpotlight(false)
            .setMultiLens(
                CameraCharacteristics.LENS_FACING_FRONT in facings &&
                    CameraCharacteristics.LENS_FACING_BACK in facings
            )
            .setAudioIn(pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
            .setAudioOut(false)
            .setSoundDetection(false)
            .setContinuousRecording(true)
            .setHardwareEncoderTier(encoderTier())
            .addAllResolutions(resolutions(cameraManager))
            .build()
    }

    private fun lensFacings(cameraManager: CameraManager?): Set<Int> = runCatching {
        cameraManager?.cameraIdList?.mapNotNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        }?.toSet()
    }.getOrNull().orEmpty()

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

    private fun resolutions(cameraManager: CameraManager?): List<Resolution> {
        val supported = backCameraSizes(cameraManager)
        val tiers = TIERS.filter { (w, h, _) ->
            supported.isEmpty() || supported.any { it.width == w && it.height == h }
        }.ifEmpty { TIERS }
        return tiers.map { (w, h, fps) ->
            Resolution.newBuilder().setWidth(w).setHeight(h).setFps(fps).build()
        }
    }

    private fun backCameraSizes(cameraManager: CameraManager?): List<Size> = runCatching {
        val id = cameraManager?.cameraIdList?.firstOrNull { candidate ->
            cameraManager.getCameraCharacteristics(candidate)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return emptyList()
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.toList()
            .orEmpty()
    }.getOrNull().orEmpty()
}
