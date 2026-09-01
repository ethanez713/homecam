package com.homecam.app

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CapabilityProvider
import com.homecam.core.rpc.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2.2: the camera's advertised capabilities must match what this device actually
 * reports, not a hardcoded list — a phone with no flash must say torch=false. Asserted
 * against the same platform queries the provider reads, so it holds on whatever device runs it.
 */
@RunWith(AndroidJUnit4::class)
class CapabilityTruthfulnessTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theProtocolVersionIsAdvertised() {
        assertEquals(Protocol.VERSION, CapabilityProvider.capabilities(context).protocolVersion)
    }

    @Test
    fun torchMatchesTheFlashFeature() {
        val expected = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        assertEquals(expected, CapabilityProvider.capabilities(context).torch)
    }

    @Test
    fun audioInMatchesTheMicrophoneFeature() {
        val expected = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        assertEquals(expected, CapabilityProvider.capabilities(context).audioIn)
    }

    @Test
    fun multiLensMatchesTheCameraManager() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facings = manager.cameraIdList.mapNotNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)
        }.toSet()
        val expected = CameraCharacteristics.LENS_FACING_FRONT in facings &&
            CameraCharacteristics.LENS_FACING_BACK in facings
        assertEquals(expected, CapabilityProvider.capabilities(context).multiLens)
    }

    @Test
    fun theCameraAdvertisesAtLeastOneResolution() {
        assertTrue(CapabilityProvider.capabilities(context).resolutionsCount > 0)
    }

    @Test
    fun featuresThisBuildLacksAreReportedFalse() {
        val capabilities = CapabilityProvider.capabilities(context)
        assertFalse(capabilities.siren)
        assertFalse(capabilities.spotlight)
        assertFalse(capabilities.soundDetection)
        assertFalse(capabilities.audioOut)
    }
}
