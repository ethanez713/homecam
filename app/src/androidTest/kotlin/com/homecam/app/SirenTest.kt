package com.homecam.app

import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CameraDiagnostics
import com.homecam.app.camera.Siren
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 8.1: the siren plays, stops itself at the timeout without further input, and a
 * second start while running does not stack. The volume tests raise STREAM_ALARM to its
 * maximum and put the previous level back. Do not run this class on a phone in use — the
 * siren is loud.
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

    @Test
    fun alarmStreamIsMaxWhileSoundingAndPriorAfterExplicitStop() {
        withObservableAlarmVolume { audio, prior, max ->
            val loud = Siren(context)
            try {
                loud.start(seconds = 30)
                loud.start(seconds = 30)
                assertTrue("siren should be playing", loud.active)
                assertAlarmVolume(audio, max, "STREAM_ALARM should be at maximum while sounding")
                loud.stop()
                assertFalse("stop ends it", loud.active)
                assertAlarmVolume(audio, prior, "STREAM_ALARM should be back to its previous level")
                assertFalse(
                    "a clean stop leaves nothing for the next start to restore",
                    Siren.recoverInterrupted(context),
                )
            } finally {
                loud.stop()
            }
        }
    }

    @Test
    fun alarmStreamIsMaxWhileSoundingAndPriorAfterTimeout() {
        withObservableAlarmVolume { audio, prior, max ->
            val loud = Siren(context)
            try {
                loud.start(seconds = 1)
                assertTrue(loud.active)
                assertAlarmVolume(audio, max, "STREAM_ALARM should be at maximum while sounding")
                val deadline = System.nanoTime() + 3_000_000_000L
                while (loud.active && System.nanoTime() < deadline) Thread.sleep(20)
                assertFalse("siren should stop on its own", loud.active)
                assertAlarmVolume(audio, prior, "STREAM_ALARM should be back to its previous level")
                assertFalse(Siren.recoverInterrupted(context))
            } finally {
                loud.stop()
            }
        }
    }

    @Test
    fun anInterruptedSirenRestoresTheSavedVolumeOnTheNextStart() {
        val dir = File(context.cacheDir, "siren-interrupted-test")
        withObservableAlarmVolume { audio, prior, max ->
            val loud = Siren(context)
            try {
                loud.start(seconds = 30)
                assertTrue(loud.active)
                assertAlarmVolume(audio, max, "STREAM_ALARM should be at maximum while sounding")
                val interrupted = Siren.recoverInterrupted(context)
                assertTrue("the next start finds the siren was interrupted", interrupted)
                assertAlarmVolume(audio, prior, "STREAM_ALARM should be back to the saved level")
                assertFalse(Siren.recoverInterrupted(context))
                dir.deleteRecursively()
                dir.mkdirs()
                CameraDiagnostics(context, dir).start("restart", sirenInterrupted = interrupted)
                val line = JSONObject(File(dir, "camera.jsonl").readLines().single { it.isNotBlank() })
                assertEquals("service_start", line.getString("event"))
                assertTrue(line.getBoolean("siren_interrupted"))
            } finally {
                loud.stop()
                dir.deleteRecursively()
            }
        }
    }

    private fun assertAlarmVolume(audio: AudioManager, expected: Int, message: String) {
        assertTrue(message, audio.getStreamVolume(AudioManager.STREAM_ALARM) == expected)
    }

    /** Lowers the alarm stream so a raise is visible, and puts the device's own level back afterwards. */
    private fun withObservableAlarmVolume(block: (AudioManager, prior: Int, max: Int) -> Unit) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val original = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, (max - 1).coerceAtLeast(0), 0)
            val prior = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            assertTrue("alarm volume must be below its maximum so the raise is observable", prior < max)
            block(audio, prior, max)
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, original, 0)
        }
    }
}
