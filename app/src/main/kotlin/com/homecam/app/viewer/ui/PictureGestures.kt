package com.homecam.app.viewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.kit.Tag
import com.homecam.core.media.Viewport

/**
 * Pinch to zoom about the fingers, and drag to pan once zoomed. One finger at one
 * times is not consumed, so a tap or a long-press still reaches whatever is under this.
 * The factor sits in the picture's top-end corner; tapping it returns to one times.
 */
@Composable
fun PictureGestures(
    viewport: Viewport,
    onViewport: (Viewport) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val latestViewport by rememberUpdatedState(viewport)
    val latestOnViewport by rememberUpdatedState(onViewport)
    Box(
        modifier.pointerInput(Unit) {
            detectPictureGestures({ latestViewport }, { latestOnViewport(it) })
        },
    ) {
        content()
        if (viewport.zoomed) {
            Tag(
                "%.1f×".format(viewport.scale),
                selected = true,
                onClick = { onViewport(Viewport.IDENTITY) },
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
        }
    }
}

private suspend fun PointerInputScope.detectPictureGestures(
    viewport: () -> Viewport,
    onViewport: (Viewport) -> Unit,
) {
    awaitEachGesture {
        var current = viewport()
        var dragged = 0f
        var panning = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            if (width <= 0f || height <= 0f) continue
            if (pressed >= 2) {
                // A pinch starts its own gesture. The one-finger slop from before it does not carry.
                panning = false
                dragged = 0f
                val centroid = event.calculateCentroid(useCurrent = true)
                if (centroid != Offset.Unspecified) {
                    val pan = event.calculatePan()
                    // Pan first, so the frame point under the fingers follows them, then zoom
                    // about the centroid, which keeps that point where the fingers are.
                    val next = current
                        .pannedBy(pan.x / width, pan.y / height)
                        .zoomedBy(event.calculateZoom(), centroid.x / width, centroid.y / height)
                    if (next != current) {
                        current = next
                        onViewport(current)
                    }
                }
                event.changes.forEach { it.consume() }
            } else if (current.zoomed) {
                val pan = event.calculatePan()
                dragged += pan.getDistance()
                if (!panning && dragged > viewConfiguration.touchSlop) panning = true
                if (panning && pan != Offset.Zero) {
                    current = current.pannedBy(pan.x / width, pan.y / height)
                    onViewport(current)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}
