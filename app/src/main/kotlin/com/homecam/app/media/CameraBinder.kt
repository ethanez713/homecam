package com.homecam.app.media

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * The process has one [ProcessCameraProvider], and [ProcessCameraProvider.unbindAll] drops
 * every use case bound to it. Capturers register here instead of binding themselves, so a
 * second lens is a second registration rather than a provider that steals the first camera.
 *
 * One registration uses the single-camera bind. Two use CameraX's concurrent bind. Any
 * change rebinds all of them, on the main executor, in registration order.
 */
class CameraBinder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val main = ContextCompat.getMainExecutor(context)
    private val slots = LinkedHashMap<Any, Slot>()
    private val futureLock = Any()

    @Volatile private var provider: ProcessCameraProvider? = null
    private var providerFuture: com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider>? = null

    fun useProvider(block: (ProcessCameraProvider) -> Unit) {
        val ready = provider
        if (ready != null) {
            main.execute { block(ready) }
            return
        }
        val future = synchronized(futureLock) {
            providerFuture ?: ProcessCameraProvider.getInstance(context).also { providerFuture = it }
        }
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider
            block(cameraProvider)
        }, main)
    }

    /**
     * Main-thread callback after a concurrent bind the HAL refused. The first slot is
     * already bound on its own; the other slots are gone, so their unregister is a no-op.
     */
    var onConcurrentBindFailed: ((Exception) -> Unit)? = null

    /**
     * [accept] is checked on the main thread before the slot is kept. A stop that was
     * queued ahead of this register, or a newer start, makes it false.
     *
     * Always posted, even from the main thread, so an unregister and the register that
     * follows it run in the order they were asked for.
     *
     * [bindFirst] inserts at the head. The primary capture sets it, so a lens or format
     * change — which unregisters and registers again — does not land it behind the other
     * lens. A refused concurrent bind keeps that first slot.
     */
    fun register(
        key: Any,
        selector: CameraSelector,
        preview: Preview,
        accept: () -> Boolean,
        bindFirst: Boolean = false,
        onCamera: (Camera) -> Unit,
    ) {
        main.execute {
            if (!accept()) return@execute
            val slot = Slot(selector, preview, onCamera)
            if (bindFirst) {
                val rest = slots.entries.filter { it.key != key }.toList()
                slots.clear()
                slots[key] = slot
                rest.forEach { (restKey, restSlot) -> slots[restKey] = restSlot }
            } else {
                slots[key] = slot
            }
            rebind()
        }
    }

    fun unregister(key: Any) {
        main.execute {
            if (slots.remove(key) != null) rebind()
        }
    }

    private fun rebind() {
        val cameraProvider = provider ?: return
        val bound = slots.entries.toList()
        cameraProvider.unbindAll()
        if (bound.isEmpty()) return
        if (bound.size == 1) {
            val slot = bound[0].value
            val camera = cameraProvider.bindToLifecycle(lifecycleOwner, slot.selector, slot.preview)
            slot.onCamera(camera)
            return
        }
        val configs = bound.map { (_, slot) ->
            ConcurrentCamera.SingleCameraConfig(
                slot.selector,
                UseCaseGroup.Builder().addUseCase(slot.preview).build(),
                lifecycleOwner,
            )
        }
        try {
            val cameras = cameraProvider.bindToLifecycle(configs).cameras
            check(cameras.size == bound.size) { "concurrent bind opened ${cameras.size} of ${bound.size} cameras" }
            bound.forEachIndexed { index, (_, slot) -> slot.onCamera(cameras[index]) }
        } catch (e: IllegalArgumentException) {
            fallBackToFirst(cameraProvider, bound, e)
        } catch (e: IllegalStateException) {
            fallBackToFirst(cameraProvider, bound, e)
        }
    }

    /** The pair was refused. Drop every slot but the first and open that one alone. */
    private fun fallBackToFirst(
        cameraProvider: ProcessCameraProvider,
        bound: List<Map.Entry<Any, Slot>>,
        error: Exception,
    ) {
        cameraProvider.unbindAll()
        val keep = bound.first().key
        slots.keys.filter { it != keep }.toList().forEach { slots.remove(it) }
        val slot = slots.getValue(keep)
        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, slot.selector, slot.preview)
        slot.onCamera(camera)
        onConcurrentBindFailed?.invoke(error)
    }

    private class Slot(
        val selector: CameraSelector,
        val preview: Preview,
        val onCamera: (Camera) -> Unit,
    )
}
