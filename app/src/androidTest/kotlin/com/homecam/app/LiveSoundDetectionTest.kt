package com.homecam.app

import android.media.AudioFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.detect.AudioDetectionPipeline
import com.homecam.proto.SettingsSnapshot
import com.homecam.proto.SoundDetection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.audio.JavaAudioDeviceModule.AudioSamples

/**
 * Phase 6.7: during a live session Android silences the sound tap, so detection has to
 * listen to the samples WebRTC captures. Those arrive on WebRTC's audio thread at 48 kHz.
 */
@RunWith(AndroidJUnit4::class)
class LiveSoundDetectionTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val events = AtomicInteger()
    private var underTest: AudioDetectionPipeline? = null

    @After
    fun tearDown() = underTest?.close() ?: Unit

    private fun pipeline(mic: Boolean, sound: Boolean = true) = AudioDetectionPipeline(
        context,
        {
            SettingsSnapshot.newBuilder()
                .setArmed(true)
                .setMicrophone(mic)
                .setSoundDetection(SoundDetection.newBuilder().setEnabled(sound))
                .build()
        },
        onEvent = { _, _, _ -> events.incrementAndGet() },
    ).also { underTest = it }

    /** 10 ms chunks, like WebRTC delivers them. */
    private fun feed(pipe: AudioDetectionPipeline, amplitude: Double, seconds: Int) {
        val rate = 48_000
        val chunk = rate / 100
        var n = 0
        repeat(seconds * 100) {
            val data = ByteArray(chunk * 2)
            for (i in 0 until chunk) {
                val v = (amplitude * 32767 * sin(2 * PI * 440 * n++ / rate)).toInt().toShort().toInt()
                data[i * 2] = v.toByte()
                data[i * 2 + 1] = (v shr 8).toByte()
            }
            pipe.onLiveSamples(AudioSamples(AudioFormat.ENCODING_PCM_16BIT, 1, rate, data))
        }
    }

    @Test
    fun aLoudSoundInWebRtcsCaptureTriggersAnEvent() {
        val pipe = pipeline(mic = true)
        pipe.setLive(true)
        feed(pipe, amplitude = 0.5, seconds = 2)
        assertEquals(1, events.get())
    }

    @Test
    fun aQuietRoomInWebRtcsCaptureTriggersNothing() {
        val pipe = pipeline(mic = true)
        pipe.setLive(true)
        feed(pipe, amplitude = 0.005, seconds = 2)
        assertEquals(0, events.get())
    }

    @Test
    fun withTheMicrophoneOffLiveSamplesAreIgnored() {
        val pipe = pipeline(mic = false)
        pipe.setLive(true)
        feed(pipe, amplitude = 0.5, seconds = 2)
        assertEquals(0, events.get())
        assertFalse(pipe.listening)
    }

    @Test
    fun theTapStaysOffWhileLiveAndComesBackAfter() {
        val pipe = pipeline(mic = true)
        pipe.setLive(true)
        assertTrue(pipe.listening)
        pipe.setLive(false)
        feed(pipe, amplitude = 0.5, seconds = 1)
        assertEquals("samples after the session ends are not the tap's business", 0, events.get())
    }
}
