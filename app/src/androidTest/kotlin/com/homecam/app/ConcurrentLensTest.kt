package com.homecam.app

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.CameraService
import com.homecam.app.camera.ConcurrentPairs
import com.homecam.app.camera.LensSource
import com.homecam.app.camera.Recorder
import com.homecam.app.data.ClipStore
import com.homecam.app.data.HomecamDatabase
import com.homecam.app.media.CameraBinder
import com.homecam.app.media.ContinuousCapture
import com.homecam.app.media.WebRtc
import com.homecam.core.lens.LensCatalog
import com.homecam.proto.LensSelection
import com.homecam.proto.RecordingMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Both lenses have to deliver frames at once, and each has to come back as its own clip.
 * A phone that cannot open this pair skips the test.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrentLensTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun bothLensesCaptureAtFullRateAndRecordOverlappingClips() {
        assumeTrue("device lacks the back-wide + front pair", deviceHasBackWideFrontPair())

        val graph = AppGraph.get(context)
        val previousLens = graph.settings.current.lens
        val previousMode = graph.settings.current.recordingMode
        val prefs = context.getSharedPreferences(LENS_PREFS, Context.MODE_PRIVATE)
        val previousPairs = prefs.getString(PAIRS_KEY, null)
        val wasRunning = CameraService.isRunning.value
        var owner: LifecycleOwner? = null
        val captures = mutableListOf<ContinuousCapture>()
        val recorders = mutableListOf<Recorder>()
        var database: HomecamDatabase? = null
        var dir: File? = null
        try {
            if (wasRunning) releaseCameraService()
            val wide = LensSource.lens(context, LensCatalog.BACK_WIDE)
            val front = LensSource.lens(context, LensCatalog.FRONT)
            check(wide != null && front != null)
            ConcurrentPairs.record(
                context,
                LensSource.lenses(context),
                listOf(ConcurrentPairs.Proven(listOf(wide.cameraId, front.cameraId), 1920, 1080)),
            )
            val stored = ConcurrentPairs.read(context)
            assertTrue(
                "stored pairs were ${stored.joinToString { "${it.a}/${it.b}" }}",
                stored.any { it.a == LensCatalog.BACK_WIDE && it.b == LensCatalog.FRONT },
            )
            runBlocking {
                graph.settings.update {
                    setLens(
                        LensSelection.newBuilder()
                            .setPrimary(LensCatalog.BACK_WIDE)
                            .addSecondary(LensCatalog.FRONT)
                            .build(),
                    )
                    setRecordingMode(RecordingMode.CONTINUOUS)
                }
            }

            val registry = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
            owner = registry
            instrumentation.runOnMainSync { registry.lifecycle.currentState = Lifecycle.State.STARTED }
            WebRtc.factory(context)
            val binder = CameraBinder(context, registry)
            val primaryCapture = ContinuousCapture(context, registry, binder)
            val secondaryCapture = ContinuousCapture(context, registry, binder)
            captures += primaryCapture
            captures += secondaryCapture
            val format = ContinuousCapture.Format(1920, 1080, 30)
            primaryCapture.start(format = format, lens = wide)
            secondaryCapture.start(format = format, lens = front)
            awaitFrames(primaryCapture, "back-wide")
            awaitFrames(secondaryCapture, "front")

            val clipDir = File(context.cacheDir, "concurrent-lens-${System.nanoTime()}").apply { mkdirs() }
            dir = clipDir
            val opened = Room.inMemoryDatabaseBuilder(context, HomecamDatabase::class.java).build()
            database = opened
            val store = ClipStore(context, opened.clips(), clipDir)
            val primaryRecorder = Recorder(graph, primaryCapture, store, LensCatalog.BACK_WIDE)
            val secondaryRecorder = Recorder(graph, secondaryCapture, store, LensCatalog.FRONT)
            recorders += primaryRecorder
            recorders += secondaryRecorder
            primaryRecorder.setMode(RecordingMode.CONTINUOUS)
            secondaryRecorder.setMode(RecordingMode.CONTINUOUS)
            primaryRecorder.start()
            secondaryRecorder.start()
            awaitRecording(primaryRecorder, "back-wide")
            awaitRecording(secondaryRecorder, "front")

            val primaryStart = primaryCapture.frameCount
            val secondaryStart = secondaryCapture.frameCount
            val started = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - started < RUN_MS) {
                primaryRecorder.tick()
                secondaryRecorder.tick()
                val remaining = RUN_MS - (SystemClock.elapsedRealtime() - started)
                if (remaining <= 0) break
                Thread.sleep(minOf(TICK_MS, remaining))
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertRate("back-wide", primaryCapture, primaryStart, elapsed)
            assertRate("front", secondaryCapture, secondaryStart, elapsed)

            primaryRecorder.stop()
            secondaryRecorder.stop()
            recorders.clear()
            val clips = awaitClips(opened)
            val kept = clips.filter { it.durationMs > 0 }
            assertEquals("clips were $clips", 2, kept.size)
            val back = kept.single { it.lensId == LensCatalog.BACK_WIDE }
            val frontClip = kept.single { it.lensId == LensCatalog.FRONT }
            val backEnd = back.startedAtMs + back.durationMs
            val frontEnd = frontClip.startedAtMs + frontClip.durationMs
            assertTrue(
                "clips do not overlap: $backEnd/$frontEnd from ${back.startedAtMs}/${frontClip.startedAtMs}",
                back.startedAtMs < frontEnd && frontClip.startedAtMs < backEnd,
            )
        } finally {
            recorders.forEach { runCatching { it.stop() } }
            captures.forEach { runCatching { it.stop() } }
            owner?.let { living ->
                instrumentation.runOnMainSync {
                    (living.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
                }
            }
            database?.close()
            dir?.deleteRecursively()
            if (previousPairs == null) prefs.edit().remove(PAIRS_KEY).commit()
            else prefs.edit().putString(PAIRS_KEY, previousPairs).commit()
            runBlocking {
                graph.settings.update {
                    setLens(previousLens)
                    setRecordingMode(previousMode)
                }
            }
            if (wasRunning) CameraService.start(context)
        }
    }

    private fun deviceHasBackWideFrontPair(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val wide = LensSource.lens(context, LensCatalog.BACK_WIDE) ?: return false
        val front = LensSource.lens(context, LensCatalog.FRONT) ?: return false
        val manager = context.getSystemService(CameraManager::class.java) ?: return false
        return manager.concurrentCameraIds.any { wide.cameraId in it && front.cameraId in it }
    }

    private fun releaseCameraService() {
        CameraService.stop(context)
        val deadline = System.currentTimeMillis() + 8_000
        while (CameraService.isRunning.value && System.currentTimeMillis() < deadline) Thread.sleep(50)
        instrumentation.runOnMainSync { }
        assertTrue("camera service did not release the cameras", !CameraService.isRunning.value)
    }

    private fun awaitFrames(capture: ContinuousCapture, what: String) {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (capture.frameCount < FRAMES && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(
            "$what delivered no frames (camera error=${capture.lastCameraErrorCode})",
            capture.frameCount >= FRAMES,
        )
    }

    private fun awaitRecording(recorder: Recorder, what: String) {
        val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
        while (!recorder.recording && System.currentTimeMillis() < deadline) {
            recorder.tick()
            Thread.sleep(200)
        }
        assertTrue("$what did not start recording", recorder.recording)
    }

    private fun assertRate(what: String, capture: ContinuousCapture, before: Long, elapsedMs: Long) {
        val frames = capture.frameCount - before
        val fps = frames * 1000.0 / elapsedMs
        assertTrue(
            "$what delivered $frames frames in ${elapsedMs}ms ($fps fps), camera error=${capture.lastCameraErrorCode}",
            fps >= 24.0,
        )
    }

    private fun awaitClips(database: HomecamDatabase) = runBlocking {
        val deadline = System.currentTimeMillis() + INDEX_TIMEOUT_MS
        var clips = database.clips().all()
        while (clips.size < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            clips = database.clips().all()
        }
        clips
    }

    private companion object {
        const val LENS_PREFS = "homecam.lens"
        const val PAIRS_KEY = "pairs"
        const val FRAMES = 5
        const val OPEN_TIMEOUT_MS = 8_000L
        const val RUN_MS = 60_000L
        const val TICK_MS = 2_000L
        const val INDEX_TIMEOUT_MS = 20_000L
    }
}
