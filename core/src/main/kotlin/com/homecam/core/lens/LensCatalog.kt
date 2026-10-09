package com.homecam.core.lens

import com.homecam.proto.Lens
import com.homecam.proto.LensInfo
import com.homecam.proto.Resolution

/** What the platform reports about one camera, or one physical member of a logical camera. */
data class CameraFacts(
    val cameraId: String,
    val front: Boolean,
    val focalMm: Float,
    val sensorWidthMm: Float,
    val zoomMin: Float,
    val zoomMax: Float,
    val hasFlash: Boolean,
    val backwardCompatible: Boolean = true,
    val physicalIds: List<String> = emptyList(),
    /** Set on a member of a logical camera; such a facts entry is never a lens of its own by id. */
    val physicalOf: String? = null,
) {
    /** Width of the scene the lens sees, as the tangent of its half-angle: comparable across sensors. */
    val view: Float get() = if (focalMm > 0f) sensorWidthMm / focalMm else 0f
}

/** A lens the camera can open, and the platform handles needed to open it. */
data class LensEntry(
    val id: String,
    val front: Boolean,
    val relativeView: Float,
    val reach: LensInfo.Reach,
    val cameraId: String,
    val physicalId: String?,
    val zoomMin: Float,
    val zoomMax: Float,
    val hasFlash: Boolean,
) {
    fun toProto(resolutions: List<Resolution>): LensInfo = LensInfo.newBuilder()
        .setId(id)
        .setFacing(if (front) Lens.Facing.FRONT else Lens.Facing.BACK)
        .setRelativeFov(relativeView)
        .setReach(reach)
        .addAllResolutions(resolutions)
        .setTorch(hasFlash)
        .setZoomMin(zoomMin)
        .setZoomMax(zoomMax)
        .build()
}

/**
 * Names the lenses a phone has. Ids are `back-wide`, `back-ultrawide` and `front`, never
 * raw camera ids, which differ per phone and per OS update. The wide lens is the first
 * back camera, the one CameraX opens by default; an ultra-wide is another way of looking
 * through the same phone, reached by the first of three means that works.
 */
object LensCatalog {

    const val BACK_WIDE = "back-wide"
    const val BACK_ULTRAWIDE = "back-ultrawide"
    const val FRONT = "front"
    const val DEFAULT = BACK_WIDE

    /** An ultra-wide must see at least this much more of the scene than the wide lens. */
    const val ULTRAWIDE_MIN_RATIO = 1.2f

    fun build(cameras: List<CameraFacts>): List<LensEntry> {
        val usable = cameras.filter { it.backwardCompatible && it.physicalOf == null && it.view > 0f }
        val wide = usable.firstOrNull { !it.front }
        val front = usable.firstOrNull { it.front }
        val ultra = wide?.let { ultraWide(it, usable, cameras) }
        return listOfNotNull(
            wide?.let { w ->
                val clampsZoom = ultra?.reach == LensInfo.Reach.ZOOM_RATIO
                entry(BACK_WIDE, w, 1f, LensInfo.Reach.CAMERA_ID, null, if (clampsZoom) 1f else w.zoomMin, w.zoomMax)
            },
            ultra,
            front?.let { entry(FRONT, it, 1f, LensInfo.Reach.CAMERA_ID, null, it.zoomMin, it.zoomMax) },
        )
    }

    private fun ultraWide(wide: CameraFacts, usable: List<CameraFacts>, all: List<CameraFacts>): LensEntry? {
        val separate = usable.filter { !it.front && it.cameraId != wide.cameraId && it.physicalIds.isEmpty() }
            .filter { it.view >= wide.view * ULTRAWIDE_MIN_RATIO }
            .maxByOrNull { it.view }
        if (separate != null) {
            return entry(BACK_ULTRAWIDE, separate, separate.view / wide.view, LensInfo.Reach.CAMERA_ID, null, separate.zoomMin, separate.zoomMax)
        }
        if (wide.zoomMin <= 1f / ULTRAWIDE_MIN_RATIO) {
            return entry(BACK_ULTRAWIDE, wide, 1f / wide.zoomMin, LensInfo.Reach.ZOOM_RATIO, null, wide.zoomMin, 1f)
        }
        val member = all.filter { it.physicalOf == wide.cameraId && it.view >= wide.view * ULTRAWIDE_MIN_RATIO }
            .maxByOrNull { it.view }
        return member?.let {
            entry(BACK_ULTRAWIDE, wide, it.view / wide.view, LensInfo.Reach.PHYSICAL_ID, it.cameraId, it.zoomMin, it.zoomMax)
        }
    }

    private fun entry(
        id: String,
        camera: CameraFacts,
        relativeView: Float,
        reach: LensInfo.Reach,
        physicalId: String?,
        zoomMin: Float,
        zoomMax: Float,
    ) = LensEntry(id, camera.front, relativeView, reach, camera.cameraId, physicalId, zoomMin, zoomMax, camera.hasFlash)
}
