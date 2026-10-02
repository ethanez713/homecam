package com.homecam.app.viewer.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.viewinterop.AndroidView
import com.homecam.app.detect.DetectionTrackReader
import com.homecam.app.ui.theme.Ethan
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
    playing: Boolean,
    seek: Seek?,
    showBoxes: Boolean,
    onPosition: (Long) -> Unit,
    onCompleted: () -> Unit,
    onUnplayable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember { PlayerHolder() }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var position by remember(file) { mutableLongStateOf(0L) }
    val latestOnPosition by rememberUpdatedState(onPosition)
    holder.onCompleted = rememberUpdatedState(onCompleted).value
    holder.onUnplayable = rememberUpdatedState(onUnplayable).value
    holder.onSize = { width, height -> if (width > 0 && height > 0) aspect = width.toFloat() / height }

    // Straight from the file: the clip carries its own boxes, so playback needs neither a
    // sidecar nor the camera to be reachable.
    val frames by produceState(emptyMap<Long, DetectionFrame>(), file) {
        value = withContext(Dispatchers.IO) { DetectionTrackReader.read(file) }
    }

    DisposableEffect(Unit) { onDispose { holder.release() } }
    LaunchedEffect(file) { holder.open(file) }
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
            )
            if (showBoxes) DetectionTrackReader.at(frames, position)?.let { BoxOverlay(it) }
        }
    }
}

/** MediaPlayer's states, kept in one place so a file, a surface and a seek can arrive in any order. */
private class PlayerHolder {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var file: File? = null
    private var prepared = false
    private var wantPlaying = false
    private var pendingSeekMs = 0L

    var onCompleted: () -> Unit = {}
    var onUnplayable: () -> Unit = {}
    var onSize: (Int, Int) -> Unit = { _, _ -> }

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
                    if (wantPlaying) ready.start()
                }
                setOnCompletionListener { onCompleted() }
                setOnErrorListener { _, _, _ -> onUnplayable(); true }
                prepareAsync()
            }
        }.getOrElse { onUnplayable(); null }
    }

    fun setPlaying(playing: Boolean) {
        wantPlaying = playing
        val current = player?.takeIf { prepared } ?: return
        runCatching { if (playing && !current.isPlaying) current.start() else if (!playing && current.isPlaying) current.pause() }
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
private fun BoxOverlay(frame: DetectionFrame) {
    val labelStyle = Ethan.type.monoSmall
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        frame.boxesList.forEach { box ->
            val left = box.left * size.width
            val top = box.top * size.height
            drawRect(
                color = BoxColor,
                topLeft = Offset(left, top),
                size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
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
