package com.homecam.core.harness

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pair a throwaway identity, open and close one session, revoke it. Not part of `:core:test`. */
class GateSecSmokeTest {

    @Test
    fun `pair a throwaway viewer, open and close a session, then revoke it`() = runBlocking {
        val invite = DeviceConfig.invite()
        val viewer = ThrowawayViewer.generate()
        assertEquals(ThrowawayViewer.LABEL, viewer.label)
        val client = HarnessClient(
            DeviceConfig.host(),
            DeviceConfig.port(),
            viewer,
            invite.cameraPublicKey.toByteArray(),
        )
        assertTrue(client.pair(invite).isNotEmpty())
        client.drop()

        client.connect()
        assertTrue(client.openSession().active)
        client.drop()

        client.connect()
        assertTrue(client.revoke())
        client.drop()
    }
}
