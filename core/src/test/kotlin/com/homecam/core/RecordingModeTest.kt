package com.homecam.core

import com.homecam.proto.ManualRecordResult
import com.homecam.proto.RecordingMode
import com.homecam.proto.RecordingModeSetting
import com.homecam.proto.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingModeTest {

    @Test
    fun `a fresh settings snapshot records on detection`() {
        // The store never sets the field on a fresh install, so the proto default is what
        // it reads — and that default must be DETECTION, not OFF.
        assertEquals(RecordingMode.DETECTION, SettingsSnapshot.getDefaultInstance().recordingMode)
    }

    @Test
    fun `the recording mode round-trips in settings and as a setter`() {
        val settings = SettingsSnapshot.newBuilder().setRecordingMode(RecordingMode.CONTINUOUS).build()
        assertEquals(RecordingMode.CONTINUOUS, SettingsSnapshot.parseFrom(settings.toByteArray()).recordingMode)

        val setter = RecordingModeSetting.newBuilder().setMode(RecordingMode.OFF).build()
        assertEquals(RecordingMode.OFF, RecordingModeSetting.parseFrom(setter.toByteArray()).mode)
    }

    @Test
    fun `a manual-record result round-trips`() {
        val result = ManualRecordResult.newBuilder()
            .setClipId("clip-1").setStartedAtMs(1_700_000_000_000L).setDurationMs(30_000).build()
        assertEquals(result, ManualRecordResult.parseFrom(result.toByteArray()))
    }
}
