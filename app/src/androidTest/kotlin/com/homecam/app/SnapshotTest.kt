package com.homecam.app

import android.graphics.BitmapFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.YuvJpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer

/**
 * Phase 1.3: a snapshot is a JPEG off the live capture whose dimensions are the capture
 * format's, and when capture is not running the caller gets an UNAVAILABLE rather than a
 * blank frame. The full running-capture path is exercised by running the app on a device;
 * these cover the two properties that can be pinned without a live camera.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun snapshotJpegDecodesToTheBufferDimensions() {
        val width = 320
        val height = 240
        val buffer = JavaI420Buffer.allocate(width, height)
        val jpeg = YuvJpeg.encode(buffer, quality = 85)
        buffer.release()

        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertNotNull("snapshot JPEG did not decode", bitmap)
        assertEquals(width, bitmap!!.width)
        assertEquals(height, bitmap.height)
    }

    @Test
    fun aStoppedCaptureYieldsNoFrameRatherThanABlankOne() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        }
        val capture = ContinuousCapture(context, owner)
        runBlocking { assertNull(capture.snapshotJpeg()) }
    }
}
