package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.PrivacyMaskProcessor
import com.homecam.app.media.WebRtc
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/**
 * The recorder, detector and snapshots hang off the mask stage, and must keep receiving
 * frames when no viewer is streaming — the state a camera spends nearly all its time in.
 */
@RunWith(AndroidJUnit4::class)
class CaptureFanOutTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun maskedSinksReceiveFramesWithNoLiveTrackAttached() {
        val source = WebRtc.factory(context).createVideoSource(false)
        val received = AtomicInteger()
        val mask = PrivacyMaskProcessor().apply { addSink { received.incrementAndGet() } }
        val observer = ContinuousCapture.maskedObserver(source, mask)

        observer.onCapturerStarted(true)
        val frame = VideoFrame(JavaI420Buffer.allocate(64, 48), 0, System.nanoTime())
        observer.onFrameCaptured(frame)
        frame.release()
        source.dispose()

        assertEquals(1, received.get())
    }
}
