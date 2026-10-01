package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.media.ClipRecorder
import com.homecam.app.media.WebRtc
import java.io.File
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/**
 * The recorder draws each frame edge to edge into its encoder's surface, so a clip is only
 * undistorted if the encoder's shape matches the frame's. A camera phone standing upright
 * produces portrait frames; the clip must come out portrait, and a flip mid-run must close
 * the open clip rather than squash the rest of it.
 */
@RunWith(AndroidJUnit4::class)
class RecorderOrientationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var recorder: ClipRecorder
    private val written = mutableListOf<ClipRecorder.Written>()
    private var clock = System.nanoTime()

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "recorder-orientation").apply { deleteRecursively(); mkdirs() }
        WebRtc.factory(context)
        recorder = ClipRecorder(dir).also {
            it.onClipWritten = { clip -> synchronized(written) { written += clip } }
            it.start(WebRtc.eglBase.eglBaseContext)
        }
    }

    @After
    fun tearDown() {
        recorder.stop()
        dir.deleteRecursively()
    }

    @Test
    fun portraitFramesRecordAPortraitClip() {
        feed(width = 1080, height = 1920, seconds = 1)
        openClip("portrait")
        feed(width = 1080, height = 1920, seconds = 2)

        val clip = recorder.endClip()!!

        assertEquals(1080 to 1920, trackSize(clip.file))
    }

    @Test
    fun aFlipMidClipClosesItAndTheNextClipTakesTheNewShape() {
        feed(width = 1920, height = 1080, seconds = 1)
        openClip("landscape")
        feed(width = 1920, height = 1080, seconds = 2)

        feed(width = 1080, height = 1920, seconds = 1)
        val closedByTheFlip = synchronized(written) { written.single() }
        openClip("after-flip")
        feed(width = 1080, height = 1920, seconds = 2)
        val after = recorder.endClip()!!

        assertEquals(1920 to 1080, trackSize(closedByTheFlip.file))
        assertEquals(1080 to 1920, trackSize(after.file))
    }

    /**
     * A clip can only open once the encoder has produced its output format, which takes a
     * few frames — the same wait the service's rollover tick absorbs in production.
     */
    private fun openClip(id: String) {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (!recorder.recording && System.currentTimeMillis() < deadline) {
            recorder.beginClip(id, System.currentTimeMillis())
            feed(width = lastWidth, height = lastHeight, frames = 3)
        }
        assertTrue("clip $id never opened", recorder.recording)
    }

    private var lastWidth = 0
    private var lastHeight = 0

    private fun feed(width: Int, height: Int, seconds: Int = 0, frames: Int = seconds * FPS) {
        lastWidth = width
        lastHeight = height
        repeat(frames) {
            val buffer = JavaI420Buffer.allocate(width, height)
            clock += 1_000_000_000L / FPS
            val frame = VideoFrame(buffer, 0, clock)
            recorder.onFrame(frame)
            frame.release()
            Thread.sleep(1000L / FPS)
        }
    }

    /** Width and height from the first `tkhd` box — the video track's, as the muxer writes it first. */
    private fun trackSize(file: File): Pair<Int, Int> {
        val bytes = file.readBytes()
        val box = indexOf(bytes, "tkhd".toByteArray())
        val version = bytes[box + 4].toInt()
        val fieldsBeforeMatrix = if (version == 1) 8 + 8 + 4 + 4 + 8 else 4 + 4 + 4 + 4 + 4
        val sizeAt = box + 4 + 4 + fieldsBeforeMatrix + 8 + 2 + 2 + 2 + 2 + 36
        val buffer = ByteBuffer.wrap(bytes, sizeAt, 8)
        return (buffer.int ushr 16) to (buffer.int ushr 16)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).first { start ->
            needle.indices.all { haystack[start + it] == needle[it] }
        }

    private companion object {
        const val FPS = 30
        const val OPEN_TIMEOUT_MS = 5_000L
    }
}
