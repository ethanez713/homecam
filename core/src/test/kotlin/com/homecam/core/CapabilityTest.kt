package com.homecam.core

import com.homecam.core.rpc.ControlGating
import com.homecam.core.rpc.Protocol
import com.homecam.proto.Capabilities
import com.homecam.proto.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityTest {

    private val full = Capabilities.newBuilder()
        .setProtocolVersion(Protocol.VERSION)
        .setTorch(true)
        .setSiren(true)
        .setSpotlight(true)
        .setMultiLens(true)
        .setAudioIn(true)
        .setAudioOut(true)
        .setSoundDetection(true)
        .setContinuousRecording(true)
        .setHardwareEncoderTier(Capabilities.EncoderTier.ENCODER_HEVC)
        .addResolutions(Resolution.newBuilder().setWidth(1920).setHeight(1080).setFps(30))
        .addResolutions(Resolution.newBuilder().setWidth(1280).setHeight(720).setFps(30))
        .build()

    @Test
    fun `capabilities round-trip over the wire`() {
        val decoded = Capabilities.parseFrom(full.toByteArray())
        assertEquals(full, decoded)
        assertEquals(2, decoded.resolutionsCount)
        assertEquals(Capabilities.EncoderTier.ENCODER_HEVC, decoded.hardwareEncoderTier)
    }

    @Test
    fun `an empty capability set hides every optional control`() {
        val gates = ControlGating.gatesFor(Capabilities.getDefaultInstance())
        assertFalse(gates.torch)
        assertFalse(gates.pushToTalk)
        assertFalse(gates.lensSwitch)
        assertFalse(gates.siren)
        assertFalse(gates.spotlight)
        assertFalse(gates.soundDetection)
    }

    @Test
    fun `a full capability set hides none`() {
        val gates = ControlGating.gatesFor(full)
        assertTrue(gates.torch)
        assertTrue(gates.pushToTalk)
        assertTrue(gates.lensSwitch)
        assertTrue(gates.siren)
        assertTrue(gates.spotlight)
        assertTrue(gates.soundDetection)
    }

    @Test
    fun `push-to-talk follows audio-out, not audio-in`() {
        val micOnly = Capabilities.newBuilder().setAudioIn(true).build()
        assertFalse(ControlGating.gatesFor(micOnly).pushToTalk)
    }
}
