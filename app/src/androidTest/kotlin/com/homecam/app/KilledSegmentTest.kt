package com.homecam.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.data.ClipStore
import com.homecam.app.data.HomecamDatabase
import com.homecam.app.media.ClipRecorder
import com.homecam.app.media.WebRtc
import com.homecam.proto.RetentionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.io.File

/**
 * What a process kill leaves behind is a fragmented file that was never closed. It plays up
 * to its last fragment, so it is a clip, not litter. The copy is taken while the recorder is
 * still writing, which is exactly the state the file is in after a kill.
 */
@RunWith(AndroidJUnit4::class)
class KilledSegmentTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private lateinit var library: File
    private lateinit var database: HomecamDatabase
    private var recorder: ClipRecorder? = null
    private var clock = System.nanoTime()

    @Before
    fun setUp() {
        work = File(context.cacheDir, "killed-segment-work").apply { deleteRecursively(); mkdirs() }
        library = File(context.cacheDir, "killed-segment-library").apply { deleteRecursively(); mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, HomecamDatabase::class.java).build()
        WebRtc.factory(context)
    }

    @After
    fun tearDown() {
        recorder?.stop()
        database.close()
        work.deleteRecursively()
        library.deleteRecursively()
    }

    @Test
    fun anUnclosedFragmentedSegmentIsIndexedAndAnEmptyOneIsDeleted() = runBlocking {
        val rec = ClipRecorder(work, ClipRecorder.Config(keyframeIntervalSeconds = 1, maxClipMs = 60_000)).also {
            it.start(WebRtc.eglBase.eglBaseContext)
            recorder = it
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!rec.recording && System.currentTimeMillis() < deadline) {
            rec.beginClip("killed-1", System.currentTimeMillis())
            feed(rec, frames = 3)
        }
        feed(rec, frames = 4 * FPS)

        val cut = File(library, "killed-1.mp4")
        File(work, "killed-1.mp4").copyTo(cut)
        cut.setLastModified(System.currentTimeMillis() - 60 * 60_000L)
        val empty = File(library, "empty-1.mp4").apply {
            writeBytes(ByteArray(0)); setLastModified(System.currentTimeMillis() - 60 * 60_000L)
        }

        val store = ClipStore(context, database.clips(), library)
        store.prune(RetentionPolicy.getDefaultInstance())

        val indexed = database.clips().byId("killed-1")
        assertNotNull("a playable segment left by a kill must be indexed", indexed)
        assertTrue("duration was ${indexed!!.durationMs}", indexed.durationMs > 1_000)
        assertTrue(cut.exists())
        assertEquals(cut.length(), indexed.sizeBytes)
        assertFalse("an empty file is litter", empty.exists())
        assertEquals(null, database.clips().byId("empty-1"))
    }

    private fun feed(rec: ClipRecorder, frames: Int) {
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
