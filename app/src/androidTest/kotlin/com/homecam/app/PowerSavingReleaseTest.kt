package com.homecam.app

import android.media.AudioFormat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.detect.AudioDetectionPipeline
import com.homecam.app.detect.DetectionPipeline
import com.homecam.app.media.CameraXVideoCapturer
import com.homecam.app.media.WebRtc
import com.homecam.core.detect.Detection
import com.homecam.core.diagnostics.PowerSavingReason
import com.homecam.core.media.PowerSaving
import com.homecam.proto.DetectionMode
import com.homecam.proto.SettingsSnapshot
import com.homecam.proto.SoundDetection
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.audio.JavaAudioDeviceModule.AudioSamples

/**
 * Each release reason brings capture back to the full rate on the triggering frame,
 * and the tick puts the idle rate back only after [PowerSaving.QUIET_MS].
 * The capturer is never started, so this does not open a camera.
 */
@RunWith(AndroidJUnit4::class)
class PowerSavingReleaseTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aDetectionReleasesTheIdleRateWithinTwoFrames() = runFixture { fixture ->
        var events = 0
        val pipeline = fixture.vision(
            onMotion = { fixture.saving.release(PowerSavingReason.MOTION) },
            onEvent = { _, _ ->
                events++
                fixture.saving.release(PowerSavingReason.DETECTION)
            },
            detect = { listOf(person()) },
        )
        fixture.push(pipeline, luma = 0)
        assertEquals(CameraXVideoCapturer.IDLE_FPS, fixture.capturer.targetFps)
        val before = fixture.frames
        fixture.push(pipeline, luma = 255)
        fixture.assertFullWithinTwoFrames(before, PowerSavingReason.DETECTION)
        assertEquals(1, events)
        fixture.assertIdleReturnsAfterQuietPeriod()
    }

    @Test
    fun movementReleasesTheIdleRateWithinTwoFramesEvenWhenNothingIsAccepted() = runFixture { fixture ->
        var events = 0
        val pipeline = fixture.vision(
            onMotion = { fixture.saving.release(PowerSavingReason.MOTION) },
            onEvent = { _, _ -> events++ },
            detect = { emptyList() },
        )
        fixture.push(pipeline, luma = 0)
        assertEquals(CameraXVideoCapturer.IDLE_FPS, fixture.capturer.targetFps)
        val before = fixture.frames
        fixture.push(pipeline, luma = 255)
        fixture.assertFullWithinTwoFrames(before, PowerSavingReason.MOTION)
        assertEquals(0, events)
        fixture.assertIdleReturnsAfterQuietPeriod()
    }

    @Test
    fun aSoundReleasesTheIdleRateBeforeTheClipAndWithinTwoFrames() = runFixture { fixture ->
        var events = 0
        var releasedBeforeClip = false
        val pipeline = AudioDetectionPipeline(
            context,
            { soundSettings() },
            onEvent = { _, _ ->
                events++
                releasedBeforeClip = !fixture.saving.isEngaged
            },
        )
        pipeline.onSound = { fixture.saving.release(PowerSavingReason.SOUND) }
        fixture.audio = pipeline
        pipeline.setLive(true)
        val before = fixture.frames
        feedLoud(pipeline)
        fixture.assertFullWithinTwoFrames(before, PowerSavingReason.SOUND)
        assertEquals(1, events)
        assertTrue(releasedBeforeClip)
        fixture.assertIdleReturnsAfterQuietPeriod()
    }

    @Test
    fun aViewerReleasesTheIdleRateWithinTwoFrames() = runFixture { fixture ->
        val before = fixture.frames
        fixture.saving.release(PowerSavingReason.VIEWER)
        fixture.assertFullWithinTwoFrames(before, PowerSavingReason.VIEWER)
        fixture.assertIdleReturnsAfterQuietPeriod()
    }

    @Test
    fun aManualClipReleasesTheIdleRateWithinTwoFrames() = runFixture { fixture ->
        val before = fixture.frames
        fixture.saving.release(PowerSavingReason.MANUAL_CLIP)
        fixture.assertFullWithinTwoFrames(before, PowerSavingReason.MANUAL_CLIP)
        fixture.assertIdleReturnsAfterQuietPeriod()
    }

    @Test
    fun releasingPowerSavingLeavesAThermalThrottleAtTheIdleRate() = runFixture { fixture ->
        fixture.capturer.setThermalThrottle(true)
        fixture.saving.release(PowerSavingReason.DETECTION)
        assertEquals(CameraXVideoCapturer.IDLE_FPS, fixture.capturer.targetFps)
        fixture.capturer.setThermalThrottle(false)
        assertEquals(fixture.fullRate, fixture.capturer.targetFps)
    }

    private fun runFixture(test: (Fixture) -> Unit) {
        WebRtc.factory(context)
        val fixture = Fixture(context)
        try {
            test(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun person() = Detection("person", 0.95f, 0.4f, 0.4f, 0.6f, 0.6f)

    private fun soundSettings() = SettingsSnapshot.newBuilder()
        .setArmed(true)
        .setMicrophone(true)
        .setSoundDetection(SoundDetection.newBuilder().setEnabled(true))
        .build()

    /** 10 ms chunks, like WebRTC delivers them. Two seconds fills a detection window. */
    private fun feedLoud(pipe: AudioDetectionPipeline) {
        val rate = 48_000
        val chunk = rate / 100
        var n = 0
        repeat(200) {
            val data = ByteArray(chunk * 2)
            for (i in 0 until chunk) {
                val sample = (0.5 * 32767 * sin(2 * PI * 440 * n++ / rate)).toInt().toShort().toInt()
                data[i * 2] = sample.toByte()
                data[i * 2 + 1] = (sample shr 8).toByte()
            }
            pipe.onLiveSamples(AudioSamples(AudioFormat.ENCODING_PCM_16BIT, 1, rate, data))
        }
    }

    private class Fixture(context: android.content.Context) {
        val capturer = CameraXVideoCapturer(context, UnusedLifecycle())
        val fullRate = capturer.targetFps
        var now = 0L
        var frames = 0
        private var pipelineNow = 0L
        private var vision: DetectionPipeline? = null
        var audio: AudioDetectionPipeline? = null
        val log = mutableListOf<Pair<Boolean, String>>()
        val saving = PowerSaving(
            nowMs = { now },
            setSaving = capturer::setPowerSaving,
            log = { engaged, reason -> log += engaged to reason },
        )

        init {
            now = PowerSaving.QUIET_MS
            saving.tick(sceneIdle = true)
            check(capturer.targetFps == CameraXVideoCapturer.IDLE_FPS)
        }

        fun vision(
            onMotion: () -> Unit,
            onEvent: (List<DetectionPipeline.LabelledDetection>, ByteArray?) -> Unit,
            detect: (android.graphics.Bitmap) -> List<Detection>,
        ): DetectionPipeline = DetectionPipeline(
            context = capturerContext,
            settings = { visionSettings() },
            onMotion = onMotion,
            nowMs = { pipelineNow },
            detect = detect,
            onEvent = onEvent,
        ).also { vision = it }

        fun push(pipeline: DetectionPipeline, luma: Int) {
            pipelineNow += 1_000
            frames++
            val frame = lumaFrame(luma)
            try {
                pipeline.onFrame(frame)
            } finally {
                frame.release()
            }
        }

        fun assertFullWithinTwoFrames(frameBeforeTrigger: Int, reason: String) {
            assertTrue(frames - frameBeforeTrigger <= 2)
            assertEquals(fullRate, capturer.targetFps)
            assertEquals(false to reason, log.last())
        }

        fun assertIdleReturnsAfterQuietPeriod() {
            val releasedAt = now
            now = releasedAt + PowerSaving.QUIET_MS - 1
            saving.tick(sceneIdle = true)
            assertEquals(fullRate, capturer.targetFps)
            now = releasedAt + PowerSaving.QUIET_MS
            saving.tick(sceneIdle = true)
            assertEquals(CameraXVideoCapturer.IDLE_FPS, capturer.targetFps)
        }

        fun close() {
            vision?.close()
            audio?.close()
            capturer.dispose()
        }

        private val capturerContext = context

        private class UnusedLifecycle : LifecycleOwner {
            private val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
    }

    private companion object {
        fun visionSettings() = SettingsSnapshot.newBuilder()
            .setArmed(true)
            .setDetectionMode(
                DetectionMode.newBuilder().setPerson(true).setMinConfidence(0.5f),
            )
            .build()

        fun lumaFrame(luma: Int): VideoFrame {
            val buffer = JavaI420Buffer.allocate(64, 48)
            val y = buffer.dataY
            for (i in 0 until y.capacity()) y.put(i, luma.toByte())
            return VideoFrame(buffer, 0, 0)
        }
    }
}
