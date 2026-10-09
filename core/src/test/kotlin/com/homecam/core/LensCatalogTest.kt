package com.homecam.core

import com.homecam.core.lens.CameraFacts
import com.homecam.core.lens.LensCatalog
import com.homecam.proto.Capabilities
import com.homecam.proto.Lens
import com.homecam.proto.LensInfo
import com.homecam.proto.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCatalogTest {

    private fun camera(
        id: String,
        front: Boolean = false,
        focal: Float = 7f,
        sensor: Float = 9.12f,
        zoomMin: Float = 1f,
        zoomMax: Float = 8f,
        flash: Boolean = !front,
        compatible: Boolean = true,
        physical: List<String> = emptyList(),
        physicalOf: String? = null,
    ) = CameraFacts(id, front, focal, sensor, zoomMin, zoomMax, flash, compatible, physical, physicalOf)

    @Test
    fun `an ultra-wide with its own camera id is reached by id`() {
        val lenses = LensCatalog.build(
            listOf(camera("0"), camera("1", front = true, focal = 3.8f, sensor = 5.1f), camera("2", focal = 2.2f, sensor = 5.32f)),
        )

        assertEquals(listOf("back-wide", "back-ultrawide", "front"), lenses.map { it.id })
        val ultra = lenses.single { it.id == "back-ultrawide" }
        assertEquals(LensInfo.Reach.CAMERA_ID, ultra.reach)
        assertEquals("2", ultra.cameraId)
        assertTrue("ultra-wide should see about 1.9x the wide", ultra.relativeView in 1.8f..2.0f)
        assertEquals(1f, lenses.first().relativeView, 0f)
    }

    @Test
    fun `a logical camera that zooms below one is reached by zoom ratio, and the wide lens stops at one`() {
        val lenses = LensCatalog.build(listOf(camera("0", zoomMin = 0.5f)))

        val wide = lenses.single { it.id == "back-wide" }
        val ultra = lenses.single { it.id == "back-ultrawide" }
        assertEquals(LensInfo.Reach.ZOOM_RATIO, ultra.reach)
        assertEquals("0", ultra.cameraId)
        assertEquals(2f, ultra.relativeView, 0.001f)
        assertEquals(0.5f, ultra.zoomMin, 0f)
        assertEquals(1f, wide.zoomMin, 0f)
    }

    @Test
    fun `a physical member of the logical camera is reached by physical id as a last resort`() {
        val lenses = LensCatalog.build(
            listOf(
                camera("0", physical = listOf("4", "5")),
                camera("4", focal = 7f, physicalOf = "0"),
                camera("5", focal = 2.2f, sensor = 5.32f, physicalOf = "0"),
            ),
        )

        val ultra = lenses.single { it.id == "back-ultrawide" }
        assertEquals(LensInfo.Reach.PHYSICAL_ID, ultra.reach)
        assertEquals("0", ultra.cameraId)
        assertEquals("5", ultra.physicalId)
    }

    @Test
    fun `reach is tried in order, so a separate id beats a zoom ratio`() {
        val lenses = LensCatalog.build(listOf(camera("0", zoomMin = 0.5f), camera("2", focal = 2.2f, sensor = 5.32f)))

        assertEquals(LensInfo.Reach.CAMERA_ID, lenses.single { it.id == "back-ultrawide" }.reach)
    }

    @Test
    fun `a depth sensor and a telephoto are not an ultra-wide`() {
        val lenses = LensCatalog.build(
            listOf(
                camera("0"),
                camera("4", focal = 3.26f, sensor = 3.2f, compatible = false),
                camera("3", focal = 19f, sensor = 6.4f),
            ),
        )

        assertEquals(listOf("back-wide"), lenses.map { it.id })
    }

    @Test
    fun `a phone with one back camera and no front has one lens`() {
        assertEquals(listOf("back-wide"), LensCatalog.build(listOf(camera("0"))).map { it.id })
        assertEquals(emptyList<String>(), LensCatalog.build(emptyList()).map { it.id })
    }

    @Test
    fun `the front lens carries no torch`() {
        val front = LensCatalog.build(listOf(camera("0"), camera("1", front = true))).single { it.id == "front" }

        assertEquals(false, front.hasFlash)
        assertEquals(false, front.toProto(emptyList()).torch)
    }

    @Test
    fun `a lens round-trips through the wire format`() {
        val resolutions = listOf(Resolution.newBuilder().setWidth(1920).setHeight(1080).setFps(30).build())
        val ultra = LensCatalog.build(listOf(camera("0"), camera("2", focal = 2.2f, sensor = 5.32f))).single { it.id == "back-ultrawide" }

        val caps = Capabilities.newBuilder().addLenses(ultra.toProto(resolutions)).build()
        val parsed = Capabilities.parseFrom(caps.toByteArray()).getLenses(0)

        assertEquals("back-ultrawide", parsed.id)
        assertEquals(Lens.Facing.BACK, parsed.facing)
        assertEquals(LensInfo.Reach.CAMERA_ID, parsed.reach)
        assertEquals(resolutions, parsed.resolutionsList)
    }
}
