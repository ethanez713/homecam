package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.media.WebRtc
import com.homecam.app.viewer.ui.CroppingVideoSink
import com.homecam.core.media.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * The live picture zooms by cropping in the sink. The crop's size is the viewport's
 * window, an unzoomed frame is handed on as the same object, and the sink releases only
 * the frame it created.
 */
@RunWith(AndroidJUnit4::class)
class CroppingVideoSinkTest {

    @Before
    fun loadNative() {
        WebRtc.factory(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun zoomedFramesAreCroppedAndOnlyTheCropIsReleased() {
        val viewport = Viewport(2f, 0.25f, 0.75f)
        listOf(0, 90, 180, 270).forEach { rotation ->
            val incoming = frame(rotation)
            var forwarded: VideoFrame? = null
            val sink = CroppingVideoSink(VideoSink { seen ->
                seen.retain()
                forwarded = seen
            })
            sink.viewport = viewport
            sink.onFrame(incoming)
            val out = forwarded!!
            val crop = viewport.bufferCrop(WIDTH, HEIGHT, rotation)
            assertNotSame(incoming, out)
            assertEquals(crop.width, out.buffer.width)
            assertEquals(crop.height, out.buffer.height)
            assertEquals(rotation, out.rotation)
            assertEquals(TIMESTAMP, out.timestampNs)
            // The crop shares the source buffer. Release it first, so its reference to the
            // source is gone before the source is checked.
            assertSoleOwner(out)
            assertSoleOwner(incoming)
        }
    }

    @Test
    fun anUnzoomedFrameIsForwardedAsTheSameObject() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val incoming = frame(rotation)
            var forwarded: VideoFrame? = null
            val sink = CroppingVideoSink(VideoSink { forwarded = it })
            sink.onFrame(incoming)
            assertSame(incoming, forwarded)
            assertSoleOwner(incoming)
        }
    }

    private fun frame(rotation: Int) = VideoFrame(JavaI420Buffer.allocate(WIDTH, HEIGHT), rotation, TIMESTAMP)

    /** Exactly one owner: the first release frees it and a second is refused. */
    private fun assertSoleOwner(frame: VideoFrame) {
        frame.release()
        assertThrows(IllegalStateException::class.java) { frame.release() }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val TIMESTAMP = 1_234_567_890L
    }
}
