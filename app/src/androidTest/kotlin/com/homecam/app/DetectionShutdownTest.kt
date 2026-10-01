package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.detect.DetectionPipeline
import com.homecam.app.media.WebRtc
import com.homecam.proto.DetectionMode
import com.homecam.proto.SettingsSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/**
 * The service closes the detector on the main thread while the capture thread may be
 * inside an inference on it. Closing the interpreter under a running inference is a native
 * use-after-free that takes the whole camera process down (SIGSEGV in
 * libtensorflowlite_jni, seen on the S20U when the camera was stopped mid-detection).
 *
 * Each round keeps inference busy on a feeder thread and closes at a different point in
 * the detect cycle; a crash fails the run outright.
 */
@RunWith(AndroidJUnit4::class)
class DetectionShutdownTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val armed = SettingsSnapshot.newBuilder()
        .setArmed(true)
        .setDetectionMode(DetectionMode.newBuilder().setPerson(true).setVehicle(true).setPet(true))
        .build()

    @Before
    fun loadNative() {
        WebRtc.factory(context)
    }

    @Test
    fun closingDuringInferenceNeverTouchesAFreedInterpreter() {
        val eventsAfterClose = AtomicInteger()
        repeat(ROUNDS) { round ->
            val closed = AtomicBoolean(false)
            val pipeline = DetectionPipeline(context, { armed }) { _, _ ->
                if (closed.get()) eventsAfterClose.incrementAndGet()
            }
            val feeding = AtomicBoolean(true)
            val feeder = Thread { feed(pipeline, feeding) }.apply { start() }

            Thread.sleep(WARM_UP_MS + round * CLOSE_STEP_MS)
            pipeline.close()
            closed.set(true)
            Thread.sleep(AFTER_CLOSE_MS)
            feeding.set(false)
            feeder.join()
        }
        assertEquals(0, eventsAfterClose.get())
    }

    /** Alternating black and white frames, so the motion gate passes every one through. */
    private fun feed(pipeline: DetectionPipeline, feeding: AtomicBoolean) {
        var white = false
        while (feeding.get()) {
            val buffer = JavaI420Buffer.allocate(WIDTH, HEIGHT)
            val luma = buffer.dataY
            val value = (if (white) 235 else 16).toByte()
            for (i in 0 until luma.capacity()) luma.put(i, value)
            val frame = VideoFrame(buffer, 0, System.nanoTime())
            pipeline.onFrame(frame)
            frame.release()
            white = !white
            Thread.sleep(FRAME_INTERVAL_MS)
        }
    }

    private companion object {
        const val ROUNDS = 8
        const val WIDTH = 640
        const val HEIGHT = 360
        const val FRAME_INTERVAL_MS = 50L
        const val WARM_UP_MS = 1_200L
        // Steps the close across one 400 ms detect interval over the rounds.
        const val CLOSE_STEP_MS = 53L
        const val AFTER_CLOSE_MS = 600L
    }
}
