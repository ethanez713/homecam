package com.homecam.core.media

import com.homecam.proto.QualityTier
import com.homecam.proto.Resolution
import kotlin.math.max
import kotlin.math.min

/**
 * Resolution, frame rate and recorder bitrate move together. A tier the device's
 * advertised resolutions cannot produce is clamped down to one they can: accepting
 * it would ask the camera for a size it will not deliver.
 */
object CaptureQuality {
    data class Profile(
        val tier: QualityTier,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateBps: Int,
    )

    val high = Profile(QualityTier.QUALITY_HIGH, 1920, 1080, 30, 6_000_000)
    val medium = Profile(QualityTier.QUALITY_MEDIUM, 1280, 720, 30, 3_000_000)
    val low = Profile(QualityTier.QUALITY_LOW, 960, 540, 15, 1_200_000)

    /** Best first, so the head of [supported] is the best the device can produce. */
    val profiles: List<Profile> = listOf(high, medium, low)

    fun profile(tier: QualityTier): Profile = profiles.firstOrNull { it.tier == tier } ?: high

    /** Tiers [resolutions] can produce, best first. */
    fun supported(resolutions: List<Resolution>): List<QualityTier> =
        profiles.filter { candidate -> resolutions.any { covers(it, candidate) } }.map { it.tier }

    fun clamp(requested: QualityTier, resolutions: List<Resolution>): QualityTier {
        val asked = profile(requested).tier
        val available = supported(resolutions)
        // Below every tier there is still a capture size to run. LOW is that floor.
        if (available.isEmpty()) return QualityTier.QUALITY_LOW
        val floor = profiles.indexOfFirst { it.tier == asked }
        return available.firstOrNull { tier -> profiles.indexOfFirst { it.tier == tier } >= floor }
            ?: available.last()
    }

    fun scaleDownBy(captureWidth: Int, captureHeight: Int, requestedWidth: Int, requestedHeight: Int): Double {
        if (requestedHeight == 0 || captureHeight <= 0) return 1.0
        val byHeight = captureHeight.toDouble() / requestedHeight.toDouble()
        val byWidth = if (requestedWidth == 0 || captureWidth <= 0) byHeight
        else captureWidth.toDouble() / requestedWidth.toDouble()
        return max(byHeight, byWidth).coerceAtLeast(1.0)
    }

    private fun covers(resolution: Resolution, profile: Profile): Boolean {
        if (resolution.fps < profile.fps) return false
        val wide = max(resolution.width, resolution.height)
        val tall = min(resolution.width, resolution.height)
        return wide >= profile.width && tall >= profile.height
    }
}
