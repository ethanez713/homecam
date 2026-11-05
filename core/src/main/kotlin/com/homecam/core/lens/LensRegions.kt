package com.homecam.core.lens

import com.homecam.proto.DetectionZone
import com.homecam.proto.PrivacyMask
import com.homecam.proto.SettingsSnapshot

/** Per-lens detection zones and privacy masks. The map key is the lens; an edit's lens id is not stored. */
object LensRegions {

    fun zoneFor(settings: SettingsSnapshot, lensId: String): DetectionZone =
        settings.detectionZonesMap[lensId] ?: DetectionZone.getDefaultInstance()

    fun maskFor(settings: SettingsSnapshot, lensId: String): PrivacyMask =
        settings.privacyMasksMap[lensId] ?: PrivacyMask.getDefaultInstance()

    /** Empty [requestedLensId] is the primary lens at the time of the call. */
    fun resolvedLens(settings: SettingsSnapshot, requestedLensId: String): String =
        requestedLensId.ifEmpty { LensSelections.primaryOf(settings) }

    /**
     * Writes [zone] into [builder] for its lens. An empty polygon removes that lens's entry.
     * Returns why the edit was refused, or null after applying it.
     */
    fun edit(builder: SettingsSnapshot.Builder, zone: DetectionZone, catalog: Collection<String>): String? {
        val lensId = resolvedLens(builder.build(), zone.lensId)
        if (lensId !in catalog) return unknown(lensId)
        if (zone.pointsCount == 0) builder.removeDetectionZones(lensId)
        else builder.putDetectionZones(lensId, zone.toBuilder().clearLensId().build())
        return null
    }

    /**
     * Writes [mask] into [builder] for its lens. No rects removes that lens's entry.
     * Returns why the edit was refused, or null after applying it.
     */
    fun edit(builder: SettingsSnapshot.Builder, mask: PrivacyMask, catalog: Collection<String>): String? {
        val lensId = resolvedLens(builder.build(), mask.lensId)
        if (lensId !in catalog) return unknown(lensId)
        if (mask.rectsCount == 0) builder.removePrivacyMasks(lensId)
        else builder.putPrivacyMasks(lensId, mask.toBuilder().clearLensId().build())
        return null
    }

    /** Rects of [a] followed by rects of [b]. An empty side adds nothing, so two empty masks stay empty. */
    fun union(a: PrivacyMask, b: PrivacyMask): PrivacyMask =
        PrivacyMask.newBuilder().addAllRects(a.rectsList).addAllRects(b.rectsList).build()

    private fun unknown(lensId: String) = "this camera has no lens named '$lensId'"
}
