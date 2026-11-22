package com.homecam.core.unlock

import com.homecam.core.rpc.Controls
import com.homecam.proto.AuditEvent
import com.homecam.proto.Capabilities
import com.homecam.proto.GestureUnlockStatus
import com.homecam.proto.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GestureUnlockWireTest {

    @Test
    fun `status rides a new snapshot field and defaults to off`() {
        assertEquals(17, AuditEvent.Kind.GESTURE_UNLOCK.number)
        assertEquals(64, Controls.Settings.GESTURE_UNLOCK)
        assertEquals(65, Controls.Settings.UNLOCK_CODE)
        assertFalse(Capabilities.getDefaultInstance().gestureUnlock)
        assertFalse(SettingsSnapshot.getDefaultInstance().hasGestureUnlock())

        val status = GestureUnlockStatus.newBuilder()
            .setEnabled(true)
            .setCodeSet(true)
            .setLockedOutUntilMs(50)
            .setFailures(2)
            .build()
        val decoded = SettingsSnapshot.parseFrom(
            SettingsSnapshot.newBuilder().setGestureUnlock(status).build().toByteArray(),
        )
        assertEquals(status, decoded.gestureUnlock)
    }
}
