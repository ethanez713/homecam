package com.homecam.app.media

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.homecam.core.lens.LensEntry
import com.homecam.proto.PrivacyMask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Capture runs for as long as the camera service does, not for as long as somebody is
 * watching. That is what lets the recorder work with nobody connected, and it is why the
 * privacy mask sits here — upstream of every consumer, so masked pixels are never
 * available to be streamed or written (§2.8).
 */
/** Open so a restart can be tested with a capturer that never binds the camera. */
open class ContinuousCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    data class Format(val width: Int, val height: Int, val framerate: Int)

    private val factory = WebRtc.factory(context)
    private val maskProcessor = PrivacyMaskProcessor()
    private val trackCounter = AtomicInteger(0)
    private val deliveredFrames = AtomicLong()

    private var helper: SurfaceTextureHelper? = null
    private var capturer: CameraXVideoCapturer? = null
    private var source: VideoSource? = null

    @Volatile var format: Format = Format(1920, 1080, 30)
        private set

    val running: Boolean get() = source != null

    /** Every frame passes here, so a stall is the camera itself and not one consumer. */
    val frameCount: Long get() = deliveredFrames.get()

    val lastCameraErrorCode: Int? get() = capturer?.lastErrorCode

    fun start(
        format: Format = this.format,
        mask: PrivacyMask = PrivacyMask.getDefaultInstance(),
        lens: LensEntry? = this.lens,
    ) {
        if (running) return
        this.format = format
        this.lens = lens
        maskProcessor.mask = mask

        val videoCapturer = createCapturer()
        val (textureHelper, videoSource) = attachCapturer(videoCapturer, format)
        if (thermalThrottle) videoCapturer.setThermalThrottle(true)
        if (powerSaving) videoCapturer.setPowerSaving(true)

        helper = textureHelper
        source = videoSource
        capturer = videoCapturer
    }

    protected open fun createCapturer(): CameraXVideoCapturer =
        CameraXVideoCapturer(context, lifecycleOwner, lens)

    protected open fun attachCapturer(
        videoCapturer: CameraXVideoCapturer,
        format: Format,
    ): Pair<SurfaceTextureHelper?, VideoSource?> {
        val textureHelper = SurfaceTextureHelper.create("homecam-capture", WebRtc.eglBase.eglBaseContext)
        val videoSource = factory.createVideoSource(false)
        val downstream = maskedObserver(videoSource, maskProcessor)
        videoCapturer.initialize(textureHelper, context, object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) = downstream.onCapturerStarted(success)
            override fun onCapturerStopped() = downstream.onCapturerStopped()
            override fun onFrameCaptured(frame: VideoFrame) {
                deliveredFrames.incrementAndGet()
                downstream.onFrameCaptured(frame)
            }
        })
        videoCapturer.startCapture(format.width, format.height, format.framerate)
        return textureHelper to videoSource
    }

    /** A track per media session; they all draw from the one capture. */
    fun newTrack(): VideoTrack? = source?.let {
        factory.createVideoTrack("homecam-video-${trackCounter.incrementAndGet()}", it)
    }

    /** The recorder attaches here, so it sees exactly the frames a viewer would. */
    fun addSink(sink: VideoSink) = maskProcessor.addSink(sink)

    fun removeSink(sink: VideoSink) = maskProcessor.removeSink(sink)

    fun setPrivacyMask(mask: PrivacyMask) { maskProcessor.mask = mask }

    val orientationDegrees: Int get() = maskProcessor.orientationDegrees

    /** Coerces to the nearest quarter-turn; anything else is not a rotation a camera does. */
    fun setOrientation(degrees: Int) {
        maskProcessor.orientationDegrees = (((degrees % 360) + 360) % 360 / 90.0).roundToInt() % 4 * 90
    }

    /**
     * A single still off the live capture, JPEG-encoded, without opening a media session.
     * It rides the same masked, oriented sink chain the recorder does, so a snapshot can
     * never reveal a region the owner masked out. Null when capture is not running or no
     * frame arrives in time — the caller turns that into an UNAVAILABLE, never a blank.
     */
    suspend fun snapshotJpeg(quality: Int = SNAPSHOT_QUALITY): ByteArray? {
        if (!running) return null
        val frameBytes = CompletableDeferred<ByteArray?>()
        val sink = VideoSink { frame ->
            if (frameBytes.isCompleted) return@VideoSink
            val i420 = frame.buffer.toI420() ?: return@VideoSink
            try {
                frameBytes.complete(YuvJpeg.encode(i420, quality))
            } catch (e: Exception) {
                frameBytes.complete(null)
            } finally {
                i420.release()
            }
        }
        maskProcessor.addSink(sink)
        return try {
            withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { frameBytes.await() }
        } finally {
            maskProcessor.removeSink(sink)
        }
    }

    fun setTorch(on: Boolean) = capturer?.setTorch(on)

    /**
     * True when the sensor rate changed. False when capture is not running or the state
     * was already set. The request is kept either way: a restart builds a new capturer
     * whose own flag starts off.
     */
    fun setPowerSaving(saving: Boolean): Boolean {
        powerSaving = saving
        return capturer?.setPowerSaving(saving) ?: false
    }

    private var powerSaving = false

    private var thermalThrottle = false

    /** Kept across a capture restart, which builds a new capturer. */
    fun setThermalThrottle(on: Boolean) {
        thermalThrottle = on
        capturer?.setThermalThrottle(on)
    }

    fun setZoom(ratio: Float) = capturer?.setZoom(ratio)

    /** Kept across a capture restart, which builds a new capturer. */
    fun setLens(next: LensEntry) {
        lens = next
        capturer?.setLens(next)
    }

    private var lens: LensEntry? = null

    fun setFormat(next: Format) {
        format = next
        capturer?.changeCaptureFormat(next.width, next.height, next.framerate)
    }

    fun stop() {
        runCatching { capturer?.dispose() }
        runCatching { source?.dispose() }
        runCatching { helper?.dispose() }
        capturer = null
        source = null
        helper = null
    }

    companion object {
        private const val SNAPSHOT_QUALITY = 85
        private const val SNAPSHOT_TIMEOUT_MS = 2_000L

        /**
         * Puts [mask] in front of [source] rather than installing it with
         * `VideoSource.setVideoProcessor`: the source asks its native adapter whether a
         * frame is wanted *before* calling a processor, and the adapter says no whenever
         * no live track is being consumed — which would starve the recorder and detector
         * every time nobody is watching.
         */
        fun maskedObserver(source: VideoSource, mask: PrivacyMaskProcessor): CapturerObserver {
            val downstream = source.capturerObserver
            mask.setSink { downstream.onFrameCaptured(it) }
            return object : CapturerObserver {
                override fun onCapturerStarted(success: Boolean) = downstream.onCapturerStarted(success)
                override fun onCapturerStopped() = downstream.onCapturerStopped()
                override fun onFrameCaptured(frame: VideoFrame) = mask.onFrameCaptured(frame)
            }
        }
    }
}
