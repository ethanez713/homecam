package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.ClipEventIndex
import com.homecam.app.data.ClipEntity
import com.homecam.app.media.ClipRecorder
import com.homecam.app.media.WebRtc
import com.homecam.proto.DetectionFrame
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/** An event is placed from the clip's own metadata track, at the second the detector wrote it. */
@RunWith(AndroidJUnit4::class)
class ClipEventSpanTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var recorder: ClipRecorder
    private var clock = System.nanoTime()

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "clip-event-span").apply { deleteRecursively(); mkdirs() }
        WebRtc.factory(context)
        recorder = ClipRecorder(dir).also { it.start(WebRtc.eglBase.eglBaseContext) }
    }

    @After
    fun tearDown() {
        recorder.stop()
        dir.deleteRecursively()
    }

    @Test
    fun aDetectionComesBackAtTheTimeItWasWritten() {
        openClip("spans")
        feed(seconds = 2)
        val detectedAtMs = System.currentTimeMillis()
        recorder.writeDetections(frameWith("person", detectedAtMs).toByteArray(), detectedAtMs)
        feed(seconds = 2)
        val written = recorder.endClip()!!

        val spans = ClipEventIndex.spans(
            ClipEntity(
                id = written.id, path = written.file.absolutePath, startedAtMs = written.startedAtMs,
                durationMs = written.durationMs, sizeBytes = written.file.length(), width = 320, height = 240,
                hasAudio = false, pinned = false, labels = "person",
            )
        )

        val person = spans.single { it.label == "person" }
        assertEquals(detectedAtMs, person.startMs)
        assertTrue("span ends after it starts and within the clip", person.endMs > person.startMs && person.endMs <= written.startedAtMs + written.durationMs)
    }

    private fun frameWith(label: String, atMs: Long) = DetectionFrame.newBuilder().setAtMs(atMs)
        .addBoxes(DetectionFrame.Box.newBuilder().setLabel(label).setConfidence(0.9f).setLeft(0.1f).setTop(0.1f).setRight(0.5f).setBottom(0.5f))
        .build()

    private fun openClip(id: String) {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (!recorder.recording && System.currentTimeMillis() < deadline) {
            recorder.beginClip(id, System.currentTimeMillis())
            feed(frames = 3)
        }
        assertTrue("clip $id never opened", recorder.recording)
    }

    private fun feed(seconds: Int = 0, frames: Int = seconds * FPS) {
        repeat(frames) {
            val frame = VideoFrame(JavaI420Buffer.allocate(WIDTH, HEIGHT), 0, clock.also { clock += 1_000_000_000L / FPS })
            recorder.onFrame(frame)
            frame.release()
            Thread.sleep(1000L / FPS)
        }
    }

    private companion object {
        const val FPS = 30
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val OPEN_TIMEOUT_MS = 5_000L
    }
}
