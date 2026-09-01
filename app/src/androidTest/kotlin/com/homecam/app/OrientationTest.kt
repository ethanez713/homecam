package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homecam.app.media.PrivacyMaskProcessor
import com.homecam.proto.PrivacyMask
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/**
 * Phase 1.1: a viewer's orientation is applied once, upstream of both the live track and
 * the recorder, by adding a quarter-turn to every delivered frame's rotation. What the
 * camera reports back in SessionStatus is exactly this value, so asserting the delivered
 * rotation asserts what a viewer would see reported.
 */
@RunWith(AndroidJUnit4::class)
class OrientationTest {

    private fun frame(rotation: Int): VideoFrame =
        VideoFrame(JavaI420Buffer.allocate(16, 16), rotation, 0L)

    private fun deliveredRotation(processor: PrivacyMaskProcessor, frame: VideoFrame): Int {
        var rotation = -1
        processor.setSink { rotation = it.rotation }
        processor.onFrameCaptured(frame)
        frame.release()
        return rotation
    }

    @Test
    fun aQuarterTurnIsAddedToAnUnmaskedFrame() {
        val processor = PrivacyMaskProcessor().apply { orientationDegrees = 90 }
        assertEquals(90, deliveredRotation(processor, frame(0)))
    }

    @Test
    fun theQuarterTurnSurvivesTheMaskPath() {
        val processor = PrivacyMaskProcessor().apply {
            orientationDegrees = 90
            mask = PrivacyMask.newBuilder()
                .addRects(
                    PrivacyMask.Rect.newBuilder().setLeft(0f).setTop(0f).setRight(0.5f).setBottom(0.5f)
                )
                .build()
        }
        assertEquals(90, deliveredRotation(processor, frame(0)))
    }

    @Test
    fun rotationWrapsAtAFullTurn() {
        val processor = PrivacyMaskProcessor().apply { orientationDegrees = 90 }
        assertEquals(0, deliveredRotation(processor, frame(270)))
    }

    @Test
    fun zeroOrientationLeavesTheFrameUntouched() {
        assertEquals(180, deliveredRotation(PrivacyMaskProcessor(), frame(180)))
    }
}
