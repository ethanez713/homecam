package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.detect.YamnetClassifier
import com.homecam.core.sound.SoundClasses
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * Phase 6.3: the shipped classifier loads with a shape that matches its label file, runs on
 * a synthetic clip, and only ever returns labels from the small user-facing set. This
 * catches a miswired model — the failure mode that produces confident nonsense rather than a
 * crash — without needing real sound, which is GATE-E's job.
 */
@RunWith(AndroidJUnit4::class)
class YamnetTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theModelLoadsAndClassifiesInVocabulary() {
        val classifier = YamnetClassifier.create(context)
        assertNotNull("yamnet failed to load — shape or label mismatch", classifier)
        classifier!!

        val waveform = FloatArray(classifier.windowSamples) {
            (sin(2.0 * PI * 440.0 * it / 16_000) * 0.5).toFloat()
        }
        val event = classifier.classify(waveform, SoundClasses.ALL.toSet(), threshold = 0f)
        if (event != null) {
            assertTrue("returned category ${event.category} is not one we ship", event.category in SoundClasses.ALL)
        }
        classifier.close()
    }
}
