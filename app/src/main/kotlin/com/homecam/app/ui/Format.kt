package com.homecam.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun formatStamp(atMs: Long): String =
    stamp.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

/** "3 minutes ago" reads better in a roster than a timestamp nobody wants to subtract. */
fun formatAgo(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (atMs <= 0) return "never"
    val seconds = (nowMs - atMs) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        seconds < 30 * 86_400 -> "${seconds / 86_400} d ago"
        else -> formatStamp(atMs)
    }
}

fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return if (unit == 0) "$bytes B" else String.format("%.1f %s", value, units[unit])
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
    else "%d:%02d".format(minutes, seconds)
}
