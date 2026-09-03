package com.homecam.app

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.detect.AudioDetectionPipeline
import com.homecam.proto.SettingsSnapshot
import com.homecam.proto.SoundDetection
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6.1 / GATE-E point 3, the privacy check: with the microphone setting off, the sound
 * tap does not listen at all — a camera that listens after being told not to is a bug of a
 * different kind from a missed detection.
 */
@RunWith(AndroidJUnit4::class)
class AudioTapPrivacyTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantMic() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    private fun pipeline(settings: SettingsSnapshot): AudioDetectionPipeline =
        AudioDetectionPipeline(context, { settings }, onEvent = { _, _ -> })

    private fun settings(mic: Boolean, sound: Boolean) = SettingsSnapshot.newBuilder()
        .setMicrophone(mic)
        .setSoundDetection(SoundDetection.newBuilder().setEnabled(sound))
        .build()

    private var underTest: AudioDetectionPipeline? = null

    @After
    fun tearDown() = underTest?.close() ?: Unit

    @Test
    fun theMicrophoneOffMeansNothingListens() {
        val pipe = pipeline(settings(mic = false, sound = true)).also { underTest = it }
        pipe.sync()
        assertFalse("the tap must not run when the mic is off", pipe.listening)
    }

    @Test
    fun withTheMicOnAndSoundDetectionOnItListens() {
        val pipe = pipeline(settings(mic = true, sound = true)).also { underTest = it }
        pipe.sync()
        assertTrue("the tap should run when both are on", pipe.listening)
    }
}
