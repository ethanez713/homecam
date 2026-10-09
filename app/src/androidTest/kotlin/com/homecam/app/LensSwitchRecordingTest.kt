package com.homecam.app

import android.media.MediaMetadataRetriever
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.LensSource
import com.homecam.app.media.ClipRecorder
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.WebRtc
import java.io.File
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A switch rebinds the camera and costs about a second without frames. The recording that
 * was running must come out playable the other side: one clip spanning the switch, or a
 * clean boundary where the shape changed.
 */
@RunWith(AndroidJUnit4::class)
class LensSwitchRecordingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var owner: LifecycleOwner
    private lateinit var capture: ContinuousCapture
    private lateinit var recorder: ClipRecorder
    private lateinit var dir: File
    private val written = mutableListOf<ClipRecorder.Written>()

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "lens-switch").apply { deleteRecursively(); mkdirs() }
        val registry = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        owner = registry
        instrumentation.runOnMainSync { (registry.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.STARTED }
        WebRtc.factory(context)
        capture = ContinuousCapture(context, owner)
        recorder = ClipRecorder(dir).also {
            it.onClipWritten = { clip -> synchronized(written) { written += clip } }
            it.start(WebRtc.eglBase.eglBaseContext)
        }
    }

    @After
    fun tearDown() {
        capture.removeSink(recorder)
        recorder.stop()
        capture.stop()
        instrumentation.runOnMainSync { (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED }
        dir.deleteRecursively()
    }

    @Test
    fun aClipRecordedAcrossALensSwitchPlaysOnBothSides() {
        capture.start()
        capture.addSink(recorder)
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (!recorder.recording && System.currentTimeMillis() < deadline) {
            recorder.beginClip("across", System.currentTimeMillis())
            Thread.sleep(200)
        }
        assertTrue("clip never opened", recorder.recording)
        val startedAt = System.currentTimeMillis()

        Thread.sleep(SETTLE_MS)
        capture.setLens(LensSource.lenses(context).single { it.id == "back-ultrawide" })
        Thread.sleep(SETTLE_MS)
        capture.setLens(LensSource.lenses(context).single { it.id == "back-wide" })
        Thread.sleep(SETTLE_MS)

        val last = recorder.endClip()
        val clips = synchronized(written) { written.toList() } + listOfNotNull(last)
        val covered = System.currentTimeMillis() - startedAt

        assertTrue("no clip was written", clips.isNotEmpty())
        clips.forEach { clip ->
            val retriever = MediaMetadataRetriever().apply { setDataSource(clip.file.absolutePath) }
            try {
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertNotNull("${clip.id} has no frame near its end", retriever.getFrameAtTime((duration - 300).coerceAtLeast(0) * 1000))
            } finally {
                retriever.release()
            }
        }
        val recordedMs = clips.sumOf { it.durationMs.toLong() }
        assertTrue("only ${recordedMs}ms of ${covered}ms was recorded across the switches", recordedMs >= covered * MIN_COVERED)
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 8_000L
        const val SETTLE_MS = 3_000L
        const val MIN_COVERED = 0.6
    }
}
