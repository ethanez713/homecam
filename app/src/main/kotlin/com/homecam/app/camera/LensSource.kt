package com.homecam.app.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.homecam.core.lens.CameraFacts
import com.homecam.core.lens.LensCatalog
import com.homecam.core.lens.LensEntry
import com.homecam.proto.Resolution

/** Reads what the phone says about its cameras and names the lenses that follow from it. */
object LensSource {

    private val TIERS = listOf(
        Triple(1920, 1080, 30),
        Triple(1280, 720, 30),
        Triple(640, 480, 30),
    )

    @Volatile private var cached: List<LensEntry>? = null

    fun lenses(context: Context): List<LensEntry> =
        cached ?: LensCatalog.build(facts(context)).also { cached = it }

    fun lens(context: Context, id: String): LensEntry? = lenses(context).firstOrNull { it.id == id }

    /** The sizes the lens's camera can produce, best tier first; the shipped tiers if it will not say. */
    fun resolutions(context: Context, lens: LensEntry): List<Resolution> {
        val supported = runCatching {
            cameraManager(context)?.getCameraCharacteristics(lens.cameraId)
                ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
                ?.toList()
        }.getOrNull().orEmpty()
        val tiers = TIERS.filter { (w, h, _) ->
            supported.isEmpty() || supported.any { it.width == w && it.height == h }
        }.ifEmpty { TIERS }
        return tiers.map(::resolution)
    }

    fun defaultResolutions(): List<Resolution> = TIERS.map(::resolution)

    private fun resolution(tier: Triple<Int, Int, Int>) =
        Resolution.newBuilder().setWidth(tier.first).setHeight(tier.second).setFps(tier.third).build()

    fun facts(context: Context): List<CameraFacts> {
        val manager = cameraManager(context) ?: return emptyList()
        val deviceHasFlash = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        return ids.flatMap { id ->
            val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return@flatMap emptyList()
            val physical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) characteristics.physicalCameraIds.toList() else emptyList()
            val members = physical.mapNotNull { member ->
                runCatching { manager.getCameraCharacteristics(member) }.getOrNull()
                    ?.let { factsOf(member, it, deviceHasFlash, physicalOf = id) }
            }
            listOfNotNull(factsOf(id, characteristics, deviceHasFlash, physicalIds = physical)) + members
        }
    }

    private fun factsOf(
        id: String,
        characteristics: CameraCharacteristics,
        deviceHasFlash: Boolean,
        physicalIds: List<String> = emptyList(),
        physicalOf: String? = null,
    ): CameraFacts? {
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: return null
        val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val sensorWidth = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f
        val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val compatible = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: true
        val flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        return CameraFacts(
            cameraId = id,
            front = facing == CameraCharacteristics.LENS_FACING_FRONT,
            focalMm = focal,
            sensorWidthMm = sensorWidth,
            zoomMin = zoom?.lower ?: 1f,
            zoomMax = zoom?.upper ?: characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            hasFlash = flash && deviceHasFlash,
            backwardCompatible = compatible,
            physicalIds = physicalIds,
            physicalOf = physicalOf,
        )
    }

    private fun cameraManager(context: Context) = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
}
