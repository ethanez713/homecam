package com.homecam.app.camera

import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import java.util.Collections
import java.util.WeakHashMap

/**
 * Throttling dims this app's own windows, and only those, by setting each window's
 * brightness override off. It writes no system setting.
 */
class ScreenDimmer {
    private val windows = Collections.newSetFromMap(WeakHashMap<Window, Boolean>())
    private val savedWindow = Collections.synchronizedMap(WeakHashMap<Window, Float>())
    private var dimmed = false
    private var closed = false

    fun track(window: Window) {
        synchronized(windows) { windows.add(window) }
        onMain { if (dimmed) dim(window) }
    }

    fun untrack(window: Window) {
        synchronized(windows) { windows.remove(window) }
        onMain { restoreWindow(window) }
    }

    fun engage() {
        onMain {
            if (closed || dimmed) return@onMain
            dimmed = true
            synchronized(windows) { windows.toList() }.forEach { dim(it) }
        }
    }

    fun release() {
        onMain {
            if (!dimmed) return@onMain
            dimmed = false
            synchronized(windows) { windows.toList() }.forEach { restoreWindow(it) }
        }
    }

    /** Service teardown. A later engage, posted before the process finishes stopping, does not dim again. */
    fun close() {
        onMain {
            closed = true
            if (!dimmed) return@onMain
            dimmed = false
            synchronized(windows) { windows.toList() }.forEach { restoreWindow(it) }
        }
    }

    private fun dim(window: Window) {
        val attributes = window.attributes
        savedWindow.putIfAbsent(window, attributes.screenBrightness)
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        window.attributes = attributes
    }

    private fun restoreWindow(window: Window) {
        val previous = savedWindow.remove(window) ?: return
        val attributes = window.attributes
        attributes.screenBrightness = previous
        window.attributes = attributes
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    companion object {
        private val early = Collections.newSetFromMap(WeakHashMap<Window, Boolean>())

        @Volatile var active: ScreenDimmer? = null
            private set

        fun attach(dimmer: ScreenDimmer) {
            active = dimmer
            val adopted = synchronized(early) { early.toList().also { early.clear() } }
            adopted.forEach { dimmer.track(it) }
        }

        fun detach(dimmer: ScreenDimmer) {
            if (active !== dimmer) return
            active = null
            // The activity does not pass through onStop when the service restarts, so its
            // window has to be waiting for the next dimmer.
            val still = synchronized(dimmer.windows) { dimmer.windows.toList() }
            synchronized(early) { early.addAll(still) }
        }

        fun register(window: Window) {
            val dimmer = active
            if (dimmer == null) synchronized(early) { early.add(window) } else dimmer.track(window)
        }

        fun unregister(window: Window) {
            synchronized(early) { early.remove(window) }
            active?.untrack(window)
        }
    }
}
