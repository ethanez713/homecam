package com.homecam.core

import com.homecam.core.lens.LensSelections
import com.homecam.proto.Lens
import com.homecam.proto.LensPair
import com.homecam.proto.LensSelection
import com.homecam.proto.Resolution
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
        assertNotNull(LensSelections.rejection(LensSelection.newBuilder().setPrimary("back-macro").build(), advertised, emptyList()))
        assertNotNull(LensSelections.rejection(LensSelection.getDefaultInstance(), advertised, emptyList()))
        assertNotNull(LensSelections.rejection(selection("front", "back-macro"), advertised, listOf(pair("front", "back-macro"))))
        assertNull(LensSelections.rejection(LensSelection.newBuilder().setPrimary("front").build(), advertised, emptyList()))
    }

    @Test
    fun `a secondary outside the proven pairs is rejected`() {
        val pairs = listOf(pair("back-wide", "front"))

        assertNotNull(LensSelections.rejection(selection("back-wide", "back-ultrawide"), advertised, pairs))
        assertNotNull(LensSelections.rejection(selection("back-ultrawide", "front"), advertised, pairs))
    }

    @Test
    fun `a secondary equal to the primary is rejected`() {
        val pairs = listOf(pair("back-wide", "back-wide"))

        assertNotNull(LensSelections.rejection(selection("back-wide", "back-wide"), advertised, pairs))
    }

    @Test
    fun `two secondaries are rejected`() {
        val pairs = listOf(pair("back-wide", "front"), pair("back-wide", "back-ultrawide"))

        assertNotNull(LensSelections.rejection(selection("back-wide", "front", "back-ultrawide"), advertised, pairs))
    }

    @Test
    fun `no proven pairs rejects every secondary`() {
        assertNotNull(LensSelections.rejection(selection("back-wide", "front"), advertised, emptyList()))
    }

    @Test
    fun `a proven pair is accepted in either order`() {
        val pairs = listOf(pair("front", "back-wide"))

        assertNull(LensSelections.rejection(selection("back-wide", "front"), advertised, pairs))
        assertNull(LensSelections.rejection(selection("front", "back-wide"), advertised, pairs))
        assertNull(LensSelections.rejection(selection("back-ultrawide"), advertised, pairs))
    }

    private fun selection(primary: String, vararg secondary: String): LensSelection =
        LensSelection.newBuilder().setPrimary(primary).addAllSecondary(secondary.toList()).build()

    private fun pair(a: String, b: String): LensPair = LensPair.newBuilder()
        .setA(a)
        .setB(b)
        .setMax(Resolution.newBuilder().setWidth(1920).setHeight(1080))
        .build()

    @Test
    fun `the facing-only control maps onto a lens id`() {
        assertEquals("front", LensSelections.idFor(Lens.Facing.FRONT))
        assertEquals("back-wide", LensSelections.idFor(Lens.Facing.BACK))
    }

    @Test
    fun `lenses read as the words a person would use`() {
        assertEquals(listOf("Wide", "Ultra-wide", "Front"), advertised.map(LensSelections::labelOf))
    }
}
