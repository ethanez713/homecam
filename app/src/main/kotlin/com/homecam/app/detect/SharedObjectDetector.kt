package com.homecam.app.detect

import android.content.Context
import android.graphics.Bitmap
import com.homecam.core.detect.Detection

/**
 * One interpreter for every lens. Each pipeline keeps its own gate and timing; they take
 * turns here. The interpreter closes when the last pipeline detaches, so a second lens
 * stopping does not tear down the one the primary is still running.
 */
class SharedObjectDetector(context: Context) {
    private val detector = ObjectDetector.create(context)
    private val lock = Any()
    private var users = 0
    private var closed = false

    fun attach(): (Bitmap) -> List<Detection> {
        synchronized(lock) {
            check(!closed)
            users++
        }
        return { bitmap ->
            synchronized(lock) {
                if (closed) emptyList() else detector?.detect(bitmap).orEmpty()
            }
        }
    }

    fun detach() {
        synchronized(lock) {
            if (closed) return
            if (users > 0) users--
            if (users == 0) {
                closed = true
                detector?.close()
            }
        }
    }
}
