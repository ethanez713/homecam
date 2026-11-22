package com.homecam.app.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.homecam.app.media.WebRtc
import com.homecam.app.ui.theme.Ethan
import com.homecam.core.media.Viewport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * A renderer must be released before its EGL context goes away and a track must be
 * detached before the renderer is released, or libwebrtc takes the process down with it.
 *
 * [viewport] is applied by cropping in the sink. A SurfaceView ignores a Compose
 * transform, and the sink does not scale the crop up: the hardware scaler fills the view.
 */
@Composable
fun VideoSurface(
    track: VideoTrack?,
    modifier: Modifier = Modifier,
    onLongPress: ((x: Float, y: Float) -> Unit)? = null,
    viewport: Viewport = Viewport.IDENTITY,
    onViewport: ((Viewport) -> Unit)? = null,
) {
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    val renderer = rememberRenderer()
    val sink = remember(renderer) {
        CroppingVideoSink(renderer) { ratio -> renderer.post { aspect = ratio } }
    }
    SideEffect { sink.viewport = viewport }

    DisposableEffect(track, sink) {
        track?.addSink(sink)
        onDispose { track?.removeSink(sink) }
    }

    // SCALE_ASPECT_FIT only letterboxes when the renderer measures itself; a Compose size
    // constraint overrides that and the frame is stretched to fill, so the ratio is kept here.
    // It is the uncropped frame's ratio. The crop is a smaller window of the same shape, and
    // measuring the cropped buffer would move the layout on a pinch.
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(aspect)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { renderer })
            if (onViewport != null) {
                PictureGestures(viewport, onViewport, Modifier.fillMaxSize()) {
                    if (onLongPress != null) {
                        FocusPress({ x, y ->
                            val frame = viewport.toFrame(x, y)
                            onLongPress(frame.x.coerceIn(0f, 1f), frame.y.coerceIn(0f, 1f))
                        }, Modifier.fillMaxSize())
                    }
                }
            } else if (onLongPress != null) {
                FocusPress(onLongPress, Modifier.fillMaxSize())
            }
        }
    }
}

/** Long-press names a point on the picture. A tap is left for whatever is behind this. */
@Composable
private fun FocusPress(onLongPress: (Float, Float) -> Unit, modifier: Modifier) {
    var ring by remember { mutableStateOf<Offset?>(null) }
    var ringId by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onLongPress)
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { offset ->
                    val x = (offset.x / size.width).coerceIn(0f, 1f)
                    val y = (offset.y / size.height).coerceIn(0f, 1f)
                    val id = ringId + 1
                    ringId = id
                    ring = offset
                    scope.launch {
                        delay(FOCUS_RING_MS)
                        if (ringId == id) ring = null
                    }
                    latest(x, y)
                },
            )
        },
    ) {
        ring?.let { at ->
            val accent = Ethan.colors.accent
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = accent,
                    radius = 28f * density,
                    center = at,
                    style = Stroke(width = 2f * density),
                )
            }
        }
    }
}

@Composable
private fun rememberRenderer(): SurfaceViewRenderer {
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            // Resolution changes are the cropped buffer's. The picture's shape comes from the sink.
            init(WebRtc.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() = Unit
                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
            })
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    return renderer
}

private const val FOCUS_RING_MS = 700L

/**
 * Forwards [viewport]'s window of each frame. Unzoomed, the caller's frame is forwarded
 * as the same object. Zoomed, the crop is a new frame, released here once it has been
 * forwarded; the incoming frame stays the caller's. A crop that is not resized shares
 * the source buffer and retains it, so releasing the crop is what drops that reference.
 */
class CroppingVideoSink(
    private val downstream: VideoSink,
    private val onUncroppedAspect: (Float) -> Unit = {},
) : VideoSink {
    @Volatile var viewport: Viewport = Viewport.IDENTITY

    private var reportedAspect = Float.NaN

    override fun onFrame(frame: VideoFrame) {
        reportAspect(frame)
        val current = viewport
        if (!current.zoomed) {
            downstream.onFrame(frame)
            return
        }
        val buffer = frame.buffer
        val crop = current.bufferCrop(buffer.width, buffer.height, frame.rotation)
        val cropped = buffer.cropAndScale(crop.x, crop.y, crop.width, crop.height, crop.width, crop.height)
        val zoomed = VideoFrame(cropped, frame.rotation, frame.timestampNs)
        try {
            downstream.onFrame(zoomed)
        } finally {
            zoomed.release()
        }
    }

    private fun reportAspect(frame: VideoFrame) {
        val buffer = frame.buffer
        if (buffer.width <= 0 || buffer.height <= 0) return
        val (width, height) = if (frame.rotation % 180 == 0) {
            buffer.width to buffer.height
        } else {
            buffer.height to buffer.width
        }
        val ratio = width.toFloat() / height
        if (ratio == reportedAspect) return
        reportedAspect = ratio
        onUncroppedAspect(ratio)
    }
}
