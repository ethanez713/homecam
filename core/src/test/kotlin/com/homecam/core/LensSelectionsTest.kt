package com.homecam.core

import com.homecam.core.lens.LensSelections
import com.homecam.proto.Lens
import com.homecam.proto.LensSelection
import com.homecam.proto.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LensSelectionsTest {

    private val advertised = listOf("back-wide", "back-ultrawide", "front")

    @Test
    fun `a fresh store reads as the back wide lens`() {
        assertEquals("back-wide", LensSelections.primaryOf(SettingsSnapshot.getDefaultInstance()))
    }

    @Test
    fun `a chosen lens survives the settings round trip`() {
        val saved = SettingsSnapshot.newBuilder().setLens(LensSelection.newBuilder().setPrimary("back-ultrawide")).build()

        assertEquals("back-ultrawide", LensSelections.primaryOf(SettingsSnapshot.parseFrom(saved.toByteArray())))
    }

    @Test
    fun `a lens the camera does not have is rejected, not ignored`() {
        assertNotNull(LensSelections.rejection(LensSelection.newBuilder().setPrimary("back-macro").build(), advertised))
        assertNotNull(LensSelections.rejection(LensSelection.getDefaultInstance(), advertised))
        assertNull(LensSelections.rejection(LensSelection.newBuilder().setPrimary("front").build(), advertised))
    }

    @Test
    fun `secondary lenses are refused until concurrent capture exists`() {
        val both = LensSelection.newBuilder().setPrimary("back-wide").addSecondary("back-ultrawide").build()

        assertNotNull(LensSelections.rejection(both, advertised))
    }

    @Test
    fun `the facing-only control maps onto a lens id`() {
        assertEquals("front", LensSelections.idFor(Lens.Facing.FRONT))
        assertEquals("back-wide", LensSelections.idFor(Lens.Facing.BACK))
    }
}
