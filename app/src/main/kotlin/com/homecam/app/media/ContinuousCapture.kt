package com.homecam.app.media

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.homecam.proto.Lens
import com.homecam.proto.PrivacyMask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Capture runs for as long as the camera service does, not for as long as somebody is
 * watching. That is what lets the recorder work with nobody connected, and it is why the
 * privacy mask sits here — upstream of every consumer, so masked pixels are never
 * available to be streamed or written (§2.8).
 */
class ContinuousCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    data class Format(val width: Int, val height: Int, val framerate: Int)

    private val factory = WebRtc.factory(context)
    private val maskProcessor = PrivacyMaskProcessor()
    private val trackCounter = AtomicInteger(0)

    private var helper: SurfaceTextureHelper? = null
    private var capturer: CameraXVideoCapturer? = null
    private var source: VideoSource? = null

    @Volatile var format: Format = Format(1280, 720, 24)
        private set

    val running: Boolean get() = source != null

    fun start(format: Format = this.format, mask: PrivacyMask = PrivacyMask.getDefaultInstance()) {
        if (running) return
        this.format = format
        maskProcessor.mask = mask

        val textureHelper = SurfaceTextureHelper.create("homecam-capture", WebRtc.eglBase.eglBaseContext)
        val videoSource = factory.createVideoSource(false)
        videoSource.setVideoProcessor(maskProcessor)

        val videoCapturer = CameraXVideoCapturer(context, lifecycleOwner)
        videoCapturer.initialize(textureHelper, context, videoSource.capturerObserver)
        videoCapturer.startCapture(format.width, format.height, format.framerate)

        helper = textureHelper
        source = videoSource
        capturer = videoCapturer
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

    fun setZoom(ratio: Float) = capturer?.setZoom(ratio)

    fun setLens(facing: Lens.Facing) = capturer?.setLensFacing(facing == Lens.Facing.FRONT)

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

    private companion object {
        const val SNAPSHOT_QUALITY = 85
        const val SNAPSHOT_TIMEOUT_MS = 2_000L
    }
}
