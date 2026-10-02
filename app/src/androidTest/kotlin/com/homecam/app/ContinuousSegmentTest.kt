package com.homecam.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.data.ClipStore
import com.homecam.app.data.HomecamDatabase
import com.homecam.app.media.ClipRecorder
import com.homecam.app.media.WebRtc
import com.homecam.proto.RetentionPolicy
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/**
 * Continuous recording is a chain of segments, and a hole anywhere in the chain is footage
 * that does not exist. Two ways it broke on the S20U: retention's orphan sweep deleted the
 * segment being written (it has no index row until it closes), and each new segment began
 * mid-GOP, so its frames up to the next keyframe were refused by the muxer.
 */
@RunWith(AndroidJUnit4::class)
class ContinuousSegmentTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private var recorder: ClipRecorder? = null
    private var clock = System.nanoTime()

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "continuous-segments").apply { deleteRecursively(); mkdirs() }
        WebRtc.factory(context)
    }

    @After
    fun tearDown() {
        recorder?.stop()
        dir.deleteRecursively()
    }

    @Test
    fun segmentsRollOverWithoutAGapAndEachOneHasVideo() {
        val written = mutableListOf<ClipRecorder.Written>()
        val ids = AtomicInteger()
        val rec = ClipRecorder(dir, ClipRecorder.Config(keyframeIntervalSeconds = 1, maxClipMs = 2_000)).also {
            it.onClipWritten = { clip -> synchronized(written) { written += clip } }
            it.nextSegmentId = { "segment-${ids.incrementAndGet()}" }
            it.start(WebRtc.eglBase.eglBaseContext)
            recorder = it
        }

        feed(rec, seconds = 1)
        val deadline = System.currentTimeMillis() + 5_000
        while (!rec.recording && System.currentTimeMillis() < deadline) {
            rec.beginClip("segment-0", System.currentTimeMillis())
            feed(rec, frames = 3)
        }
        feed(rec, seconds = 9)

        val closed = synchronized(written) { written.toList() }
        assertTrue("expected several segments, got ${closed.size}", closed.size >= 3)
        assertTrue("recording stopped between segments", rec.recording)
        closed.forEach {
            assertTrue("${it.id} has no video (${it.file.length()} B)", String(it.file.readBytes(), Charsets.ISO_8859_1).contains("moof"))
            assertTrue("${it.id} lasted ${it.durationMs}ms", it.durationMs >= 1_500)
        }
        closed.zipWithNext { a, b ->
            val gap = b.startedAtMs - (a.startedAtMs + a.durationMs)
            assertTrue("gap of ${gap}ms between ${a.id} and ${b.id}", gap in -200..200)
        }
    }

    @Test
    fun theOrphanSweepLeavesTheClipBeingWrittenAlone() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, HomecamDatabase::class.java).build()
        val store = ClipStore(context, database.clips())
        val open = File(store.directory, "test-open-segment.mp4").apply { writeBytes(ByteArray(16)) }
        val abandoned = File(store.directory, "test-abandoned.mp4").apply {
            writeBytes(ByteArray(16))
            setLastModified(System.currentTimeMillis() - 60 * 60_000L)
        }
        try {
            store.prune(RetentionPolicy.getDefaultInstance())

            assertTrue("the open segment was deleted", open.exists())
            assertFalse("an abandoned file survived", abandoned.exists())
        } finally {
            open.delete()
            abandoned.delete()
            database.close()
        }
    }

    private fun feed(rec: ClipRecorder, seconds: Int = 0, frames: Int = seconds * FPS) {
        repeat(frames) {
            val buffer = JavaI420Buffer.allocate(640, 360)
            clock += 1_000_000_000L / FPS
            val frame = VideoFrame(buffer, 0, clock)
            rec.onFrame(frame)
            frame.release()
            Thread.sleep(1000L / FPS)
        }
    }

    private companion object {
        const val FPS = 30
    }
}
