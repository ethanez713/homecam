package com.homecam.app.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import com.homecam.core.lens.LensEntry
import com.homecam.core.lens.LensRegions
import com.homecam.core.media.AutoRotation
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

/**
 * Capture runs for as long as the camera service does, not for as long as somebody is
 * watching. That is what lets the recorder work with nobody connected, and it is why the
 * privacy mask sits here — upstream of every consumer, so masked pixels are never
 * available to be streamed or written (§2.8). The timestamp overlay is burned in at
 * the same point, so it is in the stream and the recording together.
 */
/** Open so a restart can be tested with a capturer that never binds the camera. */
open class ContinuousCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val binder: CameraBinder = CameraBinder(context, lifecycleOwner),
    private val bindFirst: Boolean = false,
) {
    data class Format(val width: Int, val height: Int, val framerate: Int)

    private val factory = WebRtc.factory(context)
    private val maskProcessor = PrivacyMaskProcessor()
    private val trackCounter = AtomicInteger(0)
    private val deliveredFrames = AtomicLong()
    private val maskLock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** The mask this lens settles on once a switch is no longer covering the lens it left. */
    private var steadyMask: PrivacyMask = PrivacyMask.getDefaultInstance()

    /** Non-zero while a switch is still masking every lens it has touched. */
    private var coverToken = 0
    private var pendingDrop: Runnable? = null

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
        synchronized(maskLock) {
            coverToken = 0
            steadyMask = mask
            maskProcessor.mask = mask
        }

        val videoCapturer = createCapturer()
        videoCapturer.setZoomLock(zoomLockEnabled, zoomOnBind)
        videoCapturer.setLowLight(lowLight)
        val (textureHelper, videoSource) = attachCapturer(videoCapturer, format)
        if (thermalThrottle) videoCapturer.setThermalThrottle(true)
        if (powerSaving) videoCapturer.setPowerSaving(true)

        helper = textureHelper
        source = videoSource
        capturer = videoCapturer
    }

    protected open fun createCapturer(): CameraXVideoCapturer =
        CameraXVideoCapturer(context, lifecycleOwner, lens, binder, bindFirst)

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

    /** A track per media session; they all draw from the one capture. [trackId] keeps two lenses apart. */
    fun newTrack(trackId: String? = null): VideoTrack? = source?.let {
        factory.createVideoTrack(trackId ?: "homecam-video-${trackCounter.incrementAndGet()}", it)
    }

    /** The recorder attaches here, so it sees exactly the frames a viewer would. */
    fun addSink(sink: VideoSink) = maskProcessor.addSink(sink)

    fun removeSink(sink: VideoSink) = maskProcessor.removeSink(sink)

    fun setOsd(enabled: Boolean, alias: String) {
        maskProcessor.osdEnabled = enabled
        maskProcessor.osdAlias = alias
    }

    fun setPrivacyMask(mask: PrivacyMask) {
        synchronized(maskLock) {
            steadyMask = mask
            // A switch is still covering the lens we left. Adding rects must take effect now;
            // dropping rects waits until that cover ends, so an in-flight frame stays blanked.
            maskProcessor.mask = if (coverToken == 0) mask else LensRegions.union(maskProcessor.mask, mask)
        }
    }

    val orientationDegrees: Int get() = maskProcessor.orientationDegrees

    /** Coerces to the nearest quarter-turn; anything else is not a rotation a camera does. */
    fun setOrientation(degrees: Int) {
        maskProcessor.orientationDegrees = AutoRotation.quarter(degrees)
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

    fun setZoom(ratio: Float) {
        val position = ratio.coerceIn(0f, 1f)
        if (zoomLockEnabled) zoomOnBind = position
        capturer?.let {
            if (zoomLockEnabled) it.setZoomLock(true, position)
            it.setZoom(position)
        }
    }

    /**
     * [ratio] is restored on every bind while [enabled]. A restart builds a new capturer,
     * so the request is kept here and handed over before that capturer opens.
     */
    fun setZoomLock(enabled: Boolean, ratio: Float?) {
        zoomLockEnabled = enabled
        zoomOnBind = if (enabled) ratio?.coerceIn(0f, 1f) else null
        capturer?.setZoomLock(zoomLockEnabled, zoomOnBind)
    }

    private var zoomLockEnabled = false
    private var zoomOnBind: Float? = null

    /** The primary lens only. A secondary pipeline never sets this. */
    fun setLowLight(enabled: Boolean) {
        lowLight = enabled
        capturer?.setLowLight(enabled)
    }

    private var lowLight = false

    fun setFocus(x: Float, y: Float, auto: Boolean) {
        capturer?.setFocus(x, y, auto, orientationDegrees)
    }

    fun zoomPosition(): Float? = capturer?.zoomPosition() ?: zoomOnBind

    fun linearZoom(): Float? = capturer?.linearZoom()

    fun exposureCompensationIndex(): Int? = capturer?.exposureCompensationIndex()

    fun oneStopCompensationIndex(): Int? = capturer?.oneStopCompensationIndex()

    /**
     * Kept across a capture restart, which builds a new capturer.
     *
     * A running switch masks with the union of the outgoing mask and [incomingMask] before
     * the rebind is requested. Frames from either lens can still arrive until the new camera
     * has been bound for a second, and both masks have to cover them. A newer switch during
     * that window restarts it and widens the cover to every lens involved.
     */
    fun setLens(next: LensEntry, incomingMask: () -> PrivacyMask = { steadyMask }) {
        val active = capturer
        if (active == null || next == lens) {
            lens = next
            active?.setLens(next)
            return
        }
        val token = synchronized(maskLock) {
            val incoming = incomingMask()
            steadyMask = incoming
            maskProcessor.mask = LensRegions.union(maskProcessor.mask, incoming)
            lens = next
            coverToken = if (coverToken == Int.MAX_VALUE) 1 else coverToken + 1
            coverToken
        }
        active.setLens(next) { scheduleDrop(token) }
    }

    private fun scheduleDrop(token: Int) {
        val drop = Runnable {
            synchronized(maskLock) {
                if (coverToken != token) return@Runnable
                coverToken = 0
                pendingDrop = null
                maskProcessor.mask = steadyMask
            }
        }
        synchronized(maskLock) {
            if (coverToken != token) return
            pendingDrop?.let { mainHandler.removeCallbacks(it) }
            pendingDrop = drop
            mainHandler.postDelayed(drop, SWITCH_COVER_MS)
        }
    }

    private var lens: LensEntry? = null

    fun setFormat(next: Format) {
        format = next
        capturer?.changeCaptureFormat(next.width, next.height, next.framerate)
    }

    fun stop() {
        synchronized(maskLock) {
            coverToken = 0
            pendingDrop?.let { mainHandler.removeCallbacks(it) }
            pendingDrop = null
        }
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
        private const val SWITCH_COVER_MS = 1_000L

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
