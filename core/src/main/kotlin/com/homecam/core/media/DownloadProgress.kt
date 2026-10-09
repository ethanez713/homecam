package com.homecam.core.media

/** How much of a clip has arrived; [totalBytes] is the size the camera listed it at. */
data class DownloadProgress(val receivedBytes: Long, val totalBytes: Long) {

    /** 0..1, or null when the size is unknown and only a spinner is honest. */
    val fraction: Float? get() = if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

    val percent: Int? get() = fraction?.let { (it * 100).toInt() }
}
