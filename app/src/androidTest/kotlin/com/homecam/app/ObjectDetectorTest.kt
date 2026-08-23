package com.homecam.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.detect.ObjectDetector
import com.homecam.core.detect.DetectionPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The detector was rewritten off the TFLite Task Library onto the LiteRT interpreter,
 * which moved the label lookup and the output decoding into our own code. Both are the
 * kind of mistake that produces confident nonsense rather than a crash, so they are
 * checked on a real device against the real model rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class ObjectDetectorTest {

    private var detector: ObjectDetector? = null

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        detector = ObjectDetector.create(context)
    }

    @After
    fun tearDown() {
        detector?.close()
    }

    @Test
    fun theModelLoadsAndItsShapesAreWhatTheCodeExpects() {
        // create() returns null when the input/output shapes do not match, so a non-null
        // detector is the shape assertion.
        assertNotNull("the detector failed to build from the shipped model", detector)
    }

    @Test
    fun theLabelMapShippedBesideTheModelIsTheCocoOne() {
        val labels = context.assets.open("labelmap.txt").bufferedReader().use { it.readLines() }
            .map { it.trim() }.filter { it.isNotEmpty() }

        assertEquals(90, labels.size)
        assertEquals("person", labels.first())
        // Every category the product talks about must be reachable from this map, or the
        // detector silently never fires for it.
        listOf("person", "vehicle", "pet", "package").forEach { category ->
            assertTrue(
                "no COCO label maps to $category",
                labels.any { DetectionPolicy.category(it) == category },
            )
        }
    }

    @Test
    fun aBlankFrameProducesNoConfidentDetections() {
        val blank = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(120, 120, 120))
        }
        val detections = detector!!.detect(blank)
        detections.forEach {
            assertTrue("a flat grey frame produced ${it.label} at ${it.confidence}", it.confidence < 0.6f)
        }
    }

    @Test
    fun everyDetectionIsNormalisedAndCarriesAKnownLabel() {
        // Not trying to detect anything real — the point is that whatever comes back is
        // well-formed. A transposed box or an off-by-one label index shows up here.
        val scene = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Canvas(scene).apply {
            drawColor(Color.rgb(200, 200, 210))
            drawRect(200f, 120f, 380f, 430f, Paint().apply { color = Color.rgb(40, 40, 60) })
            drawCircle(290f, 90f, 45f, Paint().apply { color = Color.rgb(210, 180, 150) })
        }

        val labels = context.assets.open("labelmap.txt").bufferedReader().use { it.readLines() }
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        detector!!.detect(scene).forEach { detection ->
            assertTrue("unknown label ${detection.label}", detection.label in labels)
            assertTrue("confidence out of range: ${detection.confidence}", detection.confidence in 0f..1f)
            assertTrue("left ${detection.left} not normalised", detection.left in 0f..1f)
            assertTrue("top ${detection.top} not normalised", detection.top in 0f..1f)
            assertTrue("right ${detection.right} not normalised", detection.right in 0f..1f)
            assertTrue("bottom ${detection.bottom} not normalised", detection.bottom in 0f..1f)
            assertTrue("box is inverted horizontally", detection.right >= detection.left)
            assertTrue("box is inverted vertically", detection.bottom >= detection.top)
        }
    }

    @Test
    fun theSameFrameTwiceGivesTheSameAnswer() {
        // The input buffer is reused between calls; forgetting to rewind it would show up
        // as the second run disagreeing with the first.
        val frame = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(90, 140, 90))
        }
        val first = detector!!.detect(frame)
        val second = detector!!.detect(frame)

        assertEquals(first.size, second.size)
        first.zip(second).forEach { (a, b) ->
            assertEquals(a.label, b.label)
            assertEquals(a.confidence, b.confidence, 0.0001f)
            assertEquals(a.left, b.left, 0.0001f)
        }
    }
}
