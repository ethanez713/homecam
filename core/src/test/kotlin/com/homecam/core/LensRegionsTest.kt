package com.homecam.core

import com.homecam.core.lens.LensCatalog
import com.homecam.core.lens.LensRegions
import com.homecam.proto.DetectionZone
import com.homecam.proto.LensSelection
import com.homecam.proto.Point
import com.homecam.proto.PrivacyMask
import com.homecam.proto.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensRegionsTest {

    private val catalog = listOf(LensCatalog.BACK_WIDE, LensCatalog.BACK_ULTRAWIDE, LensCatalog.FRONT)

    @Test
    fun `an edit for one lens leaves the other untouched`() {
        val builder = SettingsSnapshot.newBuilder()
        assertNull(LensRegions.edit(builder, zone(LensCatalog.FRONT), catalog))
        assertNull(LensRegions.edit(builder, mask(LensCatalog.FRONT, rect(0.1f, 0.1f, 0.2f, 0.2f)), catalog))

        assertNull(LensRegions.edit(builder, zone(LensCatalog.BACK_WIDE), catalog))
        assertNull(LensRegions.edit(builder, mask(LensCatalog.BACK_WIDE, rect(0.5f, 0.5f, 0.8f, 0.8f)), catalog))

        val stored = SettingsSnapshot.parseFrom(builder.build().toByteArray())
        assertEquals(zone(LensCatalog.FRONT).toBuilder().clearLensId().build(), stored.detectionZonesMap[LensCatalog.FRONT])
        assertEquals(
            mask(LensCatalog.FRONT, rect(0.1f, 0.1f, 0.2f, 0.2f)).toBuilder().clearLensId().build(),
            stored.privacyMasksMap[LensCatalog.FRONT],
        )
        assertEquals("", stored.detectionZonesMap.getValue(LensCatalog.BACK_WIDE).lensId)
        assertEquals("", stored.privacyMasksMap.getValue(LensCatalog.BACK_WIDE).lensId)
    }

    @Test
    fun `an empty lens id lands on the primary`() {
        val front = SettingsSnapshot.newBuilder()
            .setLens(LensSelection.newBuilder().setPrimary(LensCatalog.FRONT))
        assertNull(LensRegions.edit(front, zone(""), catalog))
        assertNull(LensRegions.edit(front, mask("", rect(0f, 0f, 0.5f, 1f)), catalog))
        assertTrue(front.detectionZonesMap.containsKey(LensCatalog.FRONT))
        assertTrue(front.privacyMasksMap.containsKey(LensCatalog.FRONT))
        assertFalse(front.detectionZonesMap.containsKey(LensCatalog.BACK_WIDE))

        val unset = SettingsSnapshot.newBuilder()
        assertNull(LensRegions.edit(unset, zone(""), catalog))
        assertEquals(listOf(LensCatalog.BACK_WIDE), unset.detectionZonesMap.keys.toList())
    }

    @Test
    fun `an unknown lens is rejected`() {
        val builder = SettingsSnapshot.newBuilder()
        assertNull(LensRegions.edit(builder, zone(LensCatalog.FRONT), catalog))
        val before = builder.build().toByteArray()

        assertNotNull(LensRegions.edit(builder, zone("back-macro"), catalog))
        assertNotNull(LensRegions.edit(builder, mask("back-macro", rect(0f, 0f, 1f, 1f)), catalog))
        val phoneWithoutUltra = listOf(LensCatalog.BACK_WIDE, LensCatalog.FRONT)
        assertNotNull(LensRegions.edit(builder, zone(LensCatalog.BACK_ULTRAWIDE), phoneWithoutUltra))
        assertEquals(before.toList(), builder.build().toByteArray().toList())
    }

    @Test
    fun `an empty edit removes the entry`() {
        val builder = SettingsSnapshot.newBuilder()
        assertNull(LensRegions.edit(builder, zone(LensCatalog.FRONT), catalog))
        assertNull(LensRegions.edit(builder, mask(LensCatalog.BACK_WIDE, rect(0f, 0f, 0.2f, 0.2f)), catalog))

        assertNull(LensRegions.edit(builder, DetectionZone.newBuilder().setLensId(LensCatalog.FRONT).build(), catalog))
        assertNull(LensRegions.edit(builder, PrivacyMask.newBuilder().setLensId(LensCatalog.BACK_WIDE).build(), catalog))

        val stored = builder.build()
        assertFalse(stored.detectionZonesMap.containsKey(LensCatalog.FRONT))
        assertFalse(stored.privacyMasksMap.containsKey(LensCatalog.BACK_WIDE))
        assertEquals(DetectionZone.getDefaultInstance(), LensRegions.zoneFor(stored, LensCatalog.FRONT))
        assertEquals(PrivacyMask.getDefaultInstance(), LensRegions.maskFor(stored, LensCatalog.BACK_WIDE))
    }

    @Test
    fun `union covers both masks`() {
        val wide = mask(LensCatalog.BACK_WIDE, rect(0f, 0f, 0.25f, 0.25f))
        val front = mask(LensCatalog.FRONT, rect(0.7f, 0.1f, 0.9f, 0.4f), rect(0.7f, 0.6f, 0.9f, 0.9f))
        val covered = LensRegions.union(wide, front)

        assertEquals(wide.rectsList + front.rectsList, covered.rectsList)
        assertEquals("", covered.lensId)
        assertEquals(0, LensRegions.union(PrivacyMask.getDefaultInstance(), PrivacyMask.getDefaultInstance()).rectsCount)
        assertEquals(front.rectsList, LensRegions.union(PrivacyMask.getDefaultInstance(), front).rectsList)
    }

    private fun zone(lensId: String): DetectionZone = DetectionZone.newBuilder()
        .setLensId(lensId)
        .setEnabled(true)
        .addPoints(Point.newBuilder().setX(0f).setY(0f))
        .addPoints(Point.newBuilder().setX(1f).setY(0f))
        .addPoints(Point.newBuilder().setX(0f).setY(1f))
        .build()

    private fun mask(lensId: String, vararg rects: PrivacyMask.Rect): PrivacyMask = PrivacyMask.newBuilder()
        .setLensId(lensId)
        .addAllRects(rects.toList())
        .build()

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): PrivacyMask.Rect =
        PrivacyMask.Rect.newBuilder().setLeft(left).setTop(top).setRight(right).setBottom(bottom).build()
}
