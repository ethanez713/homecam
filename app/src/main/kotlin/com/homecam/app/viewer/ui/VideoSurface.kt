package com.homecam.app.viewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.homecam.app.media.WebRtc
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * A renderer must be released before its EGL context goes away and a track must be
 * detached before the renderer is released, or libwebrtc takes the process down with it.
 */
@Composable
fun VideoSurface(track: VideoTrack?, modifier: Modifier = Modifier) {
    val renderer = rememberRenderer()

    DisposableEffect(track, renderer) {
        track?.addSink(renderer)
        onDispose { track?.removeSink(renderer) }
    }

    AndroidView(modifier = modifier, factory = { renderer })
}

@Composable
private fun rememberRenderer(): SurfaceViewRenderer {
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = androidx.compose.runtime.remember {
        SurfaceViewRenderer(context).apply {
            init(WebRtc.eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    return renderer
}
