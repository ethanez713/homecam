package com.homecam.app.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homecam.app.ui.theme.Ethan
import com.homecam.core.media.TickSteps
import com.homecam.core.media.Timeline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val DEFAULT_MS_PER_DP = 12_000f
private const val MIN_MS_PER_DP = 250f
private const val MAX_MS_PER_DP = 400_000f

/**
 * Time runs under a fixed playhead: dragging moves the recording, not the marker, so the
 * instant being watched is always in the middle of the screen. Recorded footage is a band,
 * gaps are bare, and anything detected is marked in the accent. A pinch changes the scale.
 */
@Composable
fun TimelineRuler(
    timeline: Timeline,
    playheadMs: Long,
    msPerDp: Float,
    onScrub: (Long) -> Unit,
    onScale: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
) {
    val colors = Ethan.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = Ethan.type.micro.copy(color = colors.textFaint)
    val playheadStyle = Ethan.type.mono.copy(color = colors.onAccent, fontWeight = androidx.compose.ui.text.font.FontWeight.W500)
    val playhead by rememberUpdatedState(playheadMs)
    val scale by rememberUpdatedState(msPerDp)
    val zone = ZoneId.systemDefault()

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(colors.bgSunken)
            .pointerInput(timeline) {
                awaitEachGesture {
                    awaitFirstDown()
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) onScale((scale / zoom).coerceIn(MIN_MS_PER_DP, MAX_MS_PER_DP))
                        val panDp = event.calculatePan().x / density
                        if (panDp != 0f) onScrub(timeline.clamp(playhead - (panDp * scale).toLong()))
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    onScrubEnd()
                }
            },
    ) {
        val msPerPx = msPerDp / density
        val centre = size.width / 2
        fun x(timeMs: Long) = centre + (timeMs - playheadMs) / msPerPx
        val fromMs = playheadMs - (centre * msPerPx).toLong()
        val toMs = playheadMs + (centre * msPerPx).toLong()

        val bandTop = 24.dp.toPx()
        val bandHeight = 12.dp.toPx()
        timeline.clips.forEach { clip ->
            val end = clip.startedAtMs + clip.durationMs
            if (end < fromMs || clip.startedAtMs > toMs) return@forEach
            val left = x(clip.startedAtMs).coerceAtLeast(0f)
            val right = x(end).coerceAtMost(size.width)
            drawRect(
                color = colors.borderStrong,
                topLeft = Offset(left, bandTop),
                size = Size((right - left).coerceAtLeast(2.dp.toPx()), bandHeight),
            )
        }
        timeline.events.forEach { event ->
            if (event.endMs < fromMs || event.startMs > toMs) return@forEach
            val left = x(event.startMs).coerceAtLeast(0f)
            val right = x(event.endMs).coerceAtMost(size.width)
            drawRect(
                color = colors.accent,
                topLeft = Offset(left, bandTop),
                // A one-second event at a wide zoom would otherwise be thinner than a pixel.
                size = Size((right - left).coerceAtLeast(2.dp.toPx()), bandHeight),
            )
        }

        val steps = TickSteps.forScale(msPerDp)
        val offsetMs = zone.rules.getOffset(Instant.ofEpochMilli(playheadMs)).totalSeconds * 1000L
        var tick = (fromMs + offsetMs) / steps.minorMs * steps.minorMs - offsetMs
        val baseline = size.height
        while (tick <= toMs) {
            val local = tick + offsetMs
            val isLabel = local % steps.labelMs == 0L
            val isMajor = local % steps.majorMs == 0L
            val length = when {
                isLabel -> 14.dp.toPx()
                isMajor -> 9.dp.toPx()
                else -> 5.dp.toPx()
            }
            drawLine(
                if (isLabel) colors.textFaint else colors.borderStrong,
                Offset(x(tick), baseline - length), Offset(x(tick), baseline), strokeWidth = 1.dp.toPx(),
            )
            if (isLabel) {
                val text = measurer.measure(labelFor(tick, steps.labelMs, zone), labelStyle)
                drawText(text, topLeft = Offset(x(tick) - text.size.width / 2, baseline - length - text.size.height - 2.dp.toPx()))
            }
            tick += steps.minorMs
        }

        // The instant under the playhead, written on the marker itself.
        val now = measurer.measure(playheadClock.format(Instant.ofEpochMilli(playheadMs).atZone(zone)), playheadStyle)
        val padX = 7.dp.toPx()
        val pillHeight = now.size.height + 2.dp.toPx()
        drawLine(colors.accent, Offset(centre, pillHeight), Offset(centre, size.height), strokeWidth = 2.dp.toPx())
        drawRoundRect(
            colors.accent,
            topLeft = Offset(centre - now.size.width / 2 - padX, 0f),
            size = Size(now.size.width + 2 * padX, pillHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
        )
        drawText(now, topLeft = Offset(centre - now.size.width / 2, 1.dp.toPx()))
    }
}

private val hourMinute = DateTimeFormatter.ofPattern("HH:mm")
private val playheadClock = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dayMonth = DateTimeFormatter.ofPattern("d MMM")
private const val DAY_MS = 24 * 3_600_000L

private fun labelFor(timeMs: Long, labelStepMs: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(timeMs).atZone(zone)
    val midnight = at.hour == 0 && at.minute == 0
    return (if (labelStepMs >= DAY_MS || midnight) dayMonth else hourMinute).format(at).uppercase()
}
