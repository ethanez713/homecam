package com.homecam.core.media

import com.homecam.proto.QualityTier
import com.homecam.proto.Resolution
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureQualityTest {

    @Test
    fun `a requested tier is clamped to the best the device can produce`() {
        val resolutions = listOf(size(1280, 720, 30), size(640, 480, 30))
        assertEquals(QualityTier.QUALITY_MEDIUM, CaptureQuality.clamp(QualityTier.QUALITY_HIGH, resolutions))
        assertEquals(
            listOf(QualityTier.QUALITY_MEDIUM, QualityTier.QUALITY_LOW),
            CaptureQuality.supported(resolutions),
        )
    }

    @Test
    fun `a tier the device can produce is kept`() {
        val resolutions = listOf(size(1920, 1080, 30), size(1280, 720, 30))
        assertEquals(QualityTier.QUALITY_HIGH, CaptureQuality.clamp(QualityTier.QUALITY_HIGH, resolutions))
        assertEquals(QualityTier.QUALITY_LOW, CaptureQuality.clamp(QualityTier.QUALITY_LOW, resolutions))
        assertEquals(
            listOf(QualityTier.QUALITY_HIGH, QualityTier.QUALITY_MEDIUM, QualityTier.QUALITY_LOW),
            CaptureQuality.supported(resolutions),
        )
    }

    @Test
    fun `a resolution that is too slow does not produce a faster tier`() {
        val resolutions = listOf(size(1920, 1080, 15))
        assertEquals(listOf(QualityTier.QUALITY_LOW), CaptureQuality.supported(resolutions))
        assertEquals(QualityTier.QUALITY_LOW, CaptureQuality.clamp(QualityTier.QUALITY_HIGH, resolutions))
    }

    @Test
    fun `a portrait advertisement still covers the landscape tier`() {
        assertEquals(
            listOf(QualityTier.QUALITY_HIGH, QualityTier.QUALITY_MEDIUM, QualityTier.QUALITY_LOW),
            CaptureQuality.supported(listOf(size(1080, 1920, 30))),
        )
    }

    @Test
    fun `nothing large enough clamps to the bottom tier`() {
        assertEquals(
            QualityTier.QUALITY_LOW,
            CaptureQuality.clamp(QualityTier.QUALITY_HIGH, listOf(size(640, 480, 30))),
        )
        assertEquals(emptyList<QualityTier>(), CaptureQuality.supported(listOf(size(640, 480, 30))))
    }

    @Test
    fun `the tiers bundle size frame rate and bitrate`() {
        assertEquals(1920, CaptureQuality.high.width)
        assertEquals(1080, CaptureQuality.high.height)
        assertEquals(30, CaptureQuality.high.fps)
        assertEquals(6_000_000, CaptureQuality.high.bitrateBps)
        assertEquals(1280, CaptureQuality.medium.width)
        assertEquals(720, CaptureQuality.medium.height)
        assertEquals(30, CaptureQuality.medium.fps)
        assertEquals(3_000_000, CaptureQuality.medium.bitrateBps)
        assertEquals(960, CaptureQuality.low.width)
        assertEquals(540, CaptureQuality.low.height)
        assertEquals(15, CaptureQuality.low.fps)
        assertEquals(1_200_000, CaptureQuality.low.bitrateBps)
    }

    @Test
    fun `live scale never enlarges and height zero keeps the capture`() {
        assertEquals(1.0, CaptureQuality.scaleDownBy(1920, 1080, 1280, 0), 0.0)
        assertEquals(1.5, CaptureQuality.scaleDownBy(1920, 1080, 1280, 720), 0.0)
        assertEquals(2.0, CaptureQuality.scaleDownBy(1920, 1080, 960, 540), 0.0)
        assertEquals(1.0, CaptureQuality.scaleDownBy(1280, 720, 1920, 1080), 0.0)
        assertEquals(1.0, CaptureQuality.scaleDownBy(960, 540, 1280, 720), 0.0)
    }

    private fun size(width: Int, height: Int, fps: Int): Resolution =
        Resolution.newBuilder().setWidth(width).setHeight(height).setFps(fps).build()
}
