package com.homecam.app.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * A renderer must be released before its EGL context goes away and a track must be
 * detached before the renderer is released, or libwebrtc takes the process down with it.
 */
@Composable
fun VideoSurface(
    track: VideoTrack?,
    modifier: Modifier = Modifier,
    onLongPress: ((x: Float, y: Float) -> Unit)? = null,
) {
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    val renderer = rememberRenderer { aspect = it }

    DisposableEffect(track, renderer) {
        track?.addSink(renderer)
        onDispose { track?.removeSink(renderer) }
    }

    // SCALE_ASPECT_FIT only letterboxes when the renderer measures itself; a Compose size
    // constraint overrides that and the frame is stretched to fill, so the ratio is kept here.
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(aspect)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { renderer })
            if (onLongPress != null) FocusPress(onLongPress, Modifier.fillMaxSize())
        }
    }
}

/** Long-press names a point on the picture. Tap and pinch belong to other controls. */
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
private fun rememberRenderer(onAspect: (Float) -> Unit): SurfaceViewRenderer {
    val context = androidx.compose.ui.platform.LocalContext.current
    val latestOnAspect by rememberUpdatedState(onAspect)
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            val events = object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() = Unit
                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                    val (w, h) = if (rotation % 180 == 0) width to height else height to width
                    if (w > 0 && h > 0) post { latestOnAspect(w.toFloat() / h) }
                }
            }
            init(WebRtc.eglBase.eglBaseContext, events)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    return renderer
}

private const val FOCUS_RING_MS = 700L
