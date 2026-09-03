package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.Siren
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8.1: the siren plays, stops itself at the timeout without further input, and a
 * second start while running does not stack. Run with the alarm volume left alone so the
 * test is a brief quiet tone, not a full-volume alarm.
 */
@RunWith(AndroidJUnit4::class)
class SirenTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val siren = Siren(context, raiseVolume = false)

    @After
    fun tearDown() = siren.stop()

    @Test
    fun aSecondStartDoesNotStackWhileRunning() {
        siren.start(seconds = 3)
        assertTrue("siren should be playing", siren.active)
        siren.start(seconds = 3)
        assertTrue("still one siren after a second start", siren.active)
        siren.stop()
        assertFalse("stop ends it", siren.active)
    }

    @Test
    fun itStopsItselfAtTheTimeout() {
        siren.start(seconds = 1)
        assertTrue(siren.active)
        Thread.sleep(1_500)
        assertFalse("siren should stop on its own", siren.active)
    }
}
