package com.homecam.app.viewer.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.viewinterop.AndroidView
import com.homecam.app.detect.DetectionTrackReader
import com.homecam.app.ui.theme.Ethan
import com.homecam.core.media.Viewport
import com.homecam.proto.DetectionFrame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A request to move within the open clip; the token makes two seeks to the same spot distinct. */
data class Seek(val token: Int, val positionMs: Long)

/**
 * Plays one clip file. A TextureView rather than a VideoView: it composes like any other
 * view, so the picture can sit under a bar, over a ruler or beside a rail in either
 * orientation, which a SurfaceView's punched hole does not do on every device.
 */
@Composable
fun ClipVideo(
    file: File,
    clipStartedAtMs: Long,
    playing: Boolean,
    speed: Float,
    seek: Seek?,
    showBoxes: Boolean,
    onPosition: (Long) -> Unit,
    onCompleted: () -> Unit,
    onUnplayable: (what: Int, extra: Int) -> Unit,
    viewport: Viewport,
    onViewport: (Viewport) -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember { PlayerHolder() }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var position by remember(file) { mutableLongStateOf(0L) }
    val latestOnPosition by rememberUpdatedState(onPosition)
    val zoom by rememberUpdatedState(viewport)
    holder.onCompleted = rememberUpdatedState(onCompleted).value
    holder.onUnplayable = rememberUpdatedState(onUnplayable).value
    holder.onSize = { width, height -> if (width > 0 && height > 0) aspect = width.toFloat() / height }

    // Straight from the file: the clip carries its own boxes, so playback needs neither a
    // sidecar nor the camera to be reachable.
    val frames by produceState(emptyMap<Long, DetectionFrame>(), file) {
        value = withContext(Dispatchers.IO) { DetectionTrackReader.read(file, clipStartedAtMs) }
    }

    DisposableEffect(Unit) { onDispose { holder.release() } }
    LaunchedEffect(file) { holder.open(file) }
    LaunchedEffect(speed) { holder.setSpeed(speed) }
    LaunchedEffect(playing, file) { holder.setPlaying(playing) }
    LaunchedEffect(seek) { seek?.let { holder.seekTo(it.positionMs) } }
    LaunchedEffect(file) {
        while (true) {
            holder.positionMs()?.let { position = it; latestOnPosition(it) }
            delay(POSITION_POLL_MS)
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val wide = aspect > maxWidth.value / maxHeight.value
        Box(
            if (wide) Modifier.fillMaxWidth().aspectRatio(aspect)
            else Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    TextureView(context).apply {
                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            (v as TextureView).show(zoom)
                        }
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                holder.attach(Surface(texture))
                            }
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                holder.detach()
                                return true
                            }
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
                update = { view -> view.show(zoom) },
            )
            if (showBoxes) DetectionTrackReader.at(frames, position)?.let { BoxOverlay(it, viewport) }
            PictureGestures(viewport, onViewport, Modifier.fillMaxSize())
        }
    }
}

/** Scale, then move [viewport]'s window so its top-left sits on the view's origin. */
private fun TextureView.show(viewport: Viewport) {
    val w = width
    val h = height
    if (w == 0 || h == 0) return
    val window = viewport.visible()
    setTransform(Matrix().apply {
        setScale(viewport.scale, viewport.scale)
        postTranslate(-window.left * viewport.scale * w, -window.top * viewport.scale * h)
    })
}

/** MediaPlayer's states, kept in one place so a file, a surface and a seek can arrive in any order. */
private class PlayerHolder {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var file: File? = null
    private var prepared = false
    private var wantPlaying = false
    private var pendingSeekMs = 0L
    private var speed = 1f

    var onCompleted: () -> Unit = {}
    var onUnplayable: (Int, Int) -> Unit = { _, _ -> }
    var onSize: (Int, Int) -> Unit = { _, _ -> }
    private var playbackFailed = false

    fun attach(target: Surface) {
        surface = target
        file?.let(::open)
    }

    fun detach() {
        releasePlayer()
        surface?.release()
        surface = null
    }

    fun open(clip: File) {
        file = clip
        val target = surface ?: return
        playbackFailed = false
        releasePlayer()
        player = runCatching {
            MediaPlayer().apply {
                setSurface(target)
                setDataSource(clip.absolutePath)
                setOnVideoSizeChangedListener { _, width, height -> onSize(width, height) }
                setOnPreparedListener { ready ->
                    prepared = true
                    onSize(ready.videoWidth, ready.videoHeight)
                    // A seek draws a frame; a prepared player that has not started draws nothing.
                    ready.seekTo(pendingSeekMs.coerceAtLeast(1), MediaPlayer.SEEK_CLOSEST)
                    pendingSeekMs = 0
                    if (wantPlaying) runCatching { ready.startWithSpeed() }
                }
                setOnCompletionListener { onCompleted() }
                setOnErrorListener { _, what, extra -> fail(what, extra); true }
                prepareAsync()
            }
        }.getOrElse { fail(0, 0); null }
    }

    private fun fail(what: Int, extra: Int) {
        if (playbackFailed) return
        playbackFailed = true
        onUnplayable(what, extra)
    }

    fun setPlaying(playing: Boolean) {
        wantPlaying = playing
        val current = player?.takeIf { prepared } ?: return
        runCatching {
            if (playing && !current.isPlaying) current.startWithSpeed()
            else if (!playing && current.isPlaying) current.pause()
        }
    }

    fun setSpeed(value: Float) {
        speed = value
        val current = player?.takeIf { prepared } ?: return
        // A non-zero PlaybackParams speed starts a prepared or paused player, so a change
        // while paused is only stored and applied with the next start.
        runCatching { if (current.isPlaying) current.applySpeed() }
    }

    private fun MediaPlayer.startWithSpeed() {
        applySpeed()
        start()
    }

    private fun MediaPlayer.applySpeed() {
        playbackParams = PlaybackParams().setSpeed(speed)
    }

    fun seekTo(positionMs: Long) {
        val current = player?.takeIf { prepared }
        if (current == null) pendingSeekMs = positionMs
        else runCatching { current.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST) }
    }

    fun positionMs(): Long? = player?.takeIf { prepared }?.let { runCatching { it.currentPosition.toLong() }.getOrNull() }

    fun release() {
        releasePlayer()
        file = null
    }

    private fun releasePlayer() {
        prepared = false
        runCatching { player?.release() }
        player = null
    }
}

private const val POSITION_POLL_MS = 100L

@Composable
private fun BoxOverlay(frame: DetectionFrame, viewport: Viewport) {
    val labelStyle = Ethan.type.monoSmall
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val window = viewport.visible()
        val spanX = window.right - window.left
        val spanY = window.bottom - window.top
        frame.boxesList.forEach { box ->
            val left = (box.left - window.left) / spanX * size.width
            val top = (box.top - window.top) / spanY * size.height
            val right = (box.right - window.left) / spanX * size.width
            val bottom = (box.bottom - window.top) / spanY * size.height
            if (right <= 0f || bottom <= 0f || left >= size.width || top >= size.height) return@forEach
            drawRect(
                color = BoxColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 3f),
            )
            drawText(
                measurer,
                "${box.label} ${(box.confidence * 100).toInt()}%",
                topLeft = Offset(left, (top - 36f).coerceAtLeast(0f)),
                style = labelStyle.copy(color = BoxColor),
            )
        }
    }
}

// Drawn over footage rather than paper, so it is the one colour not taken from the theme.
private val BoxColor = Color(0xFFFFD98A)
