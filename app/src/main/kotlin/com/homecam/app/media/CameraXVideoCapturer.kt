package com.homecam.app.media

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.homecam.core.lens.FocusMap
import com.homecam.core.lens.LensEntry
import com.homecam.core.lens.ZoomMap
import kotlin.math.roundToInt
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

/**
 * The CameraX-to-WebRTC bridge §6 flags as the project's main technical risk.
 *
 * The trick that makes it small: WebRTC's [SurfaceTextureHelper] already owns a
 * SurfaceTexture and already knows how to turn its frames into texture-backed
 * [org.webrtc.VideoFrame]s. So rather than shuttling byte arrays, CameraX's `Preview` is
 * pointed at a Surface wrapping *that* texture, and frames arrive in WebRTC's own format
 * having never been copied through the CPU.
 *
 * If a device fights this, the fallback is WebRTC's own Camera2 capturer; nothing above
 * this class depends on which one is in use.
 */
class CameraXVideoCapturer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private var lens: LensEntry? = null,
    private val binder: CameraBinder = CameraBinder(context, lifecycleOwner),
    private val bindFirst: Boolean = false,
) : VideoCapturer {
    private var format = Triple(1920, 1080, 30)
    @Volatile private var powerSaving = false
    @Volatile private var thermalThrottle = false

    /**
     * A rebind opens a new session, which has no torch. Zoom starts at the lens minimum
     * unless the zoom-in lock asked this bind to keep the position the viewer left.
     */
    @Volatile private var userZoom: Float? = null
    @Volatile private var torchOn = false
    @Volatile private var zoomLockEnabled = false
    @Volatile private var zoomOnBind: Float? = null
    @Volatile private var lowLight = false
    /** Sensor orientation of the bound lens. The viewer's point is on the upright picture. */
    @Volatile private var sensorRotationDegrees = 0
    @Volatile private var bindGeneration = 0

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var preview: Preview? = null
    private var surface: Surface? = null
    @Volatile private var camera: Camera? = null
    private var stateSource: LiveData<CameraState>? = null

    /** Latest [CameraState] error from the bound camera. Cleared once it is open again. */
    @Volatile var lastErrorCode: Int? = null
        private set

    private val stateObserver = Observer<CameraState> { state ->
        val code = state.error?.code
        if (code != null) {
            lastErrorCode = code
        } else if (state.type == CameraState.Type.OPEN) {
            lastErrorCode = null
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver,
    ) {
        helper = surfaceTextureHelper
        observer = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        openCapture(width, height, framerate, onBound = null)
    }

    private fun openCapture(width: Int, height: Int, framerate: Int, onBound: (() -> Unit)?) {
        format = Triple(width, height, framerate)
        val helper = helper ?: return
        val observer = observer ?: return
        userZoom = if (zoomLockEnabled) zoomOnBind else null
        torchOn = false
        val generation = ++bindGeneration

        helper.startListening { frame -> observer.onFrameCaptured(frame) }
        observer.onCapturerStarted(true)

        val main = ContextCompat.getMainExecutor(context)
        binder.useProvider { cameraProvider ->
            if (generation != bindGeneration) return@useProvider

            val useCase = Preview.Builder()
                .also(::leaveOutWhatAFixedCameraDoesNotNeed)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        // CameraX defaults to 4:3; the texture it fills is sized 16:9, so a
                        // 4:3 stream arrives squashed.
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(width, height),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .build()

            val selector = selectorFor(lens)
            val sensorRotation = selector.filter(cameraProvider.availableCameraInfos)
                .firstOrNull()?.sensorRotationDegrees ?: 0
            sensorRotationDegrees = sensorRotation

            useCase.setSurfaceProvider(main) { request: SurfaceRequest ->
                // The camera writes sensor-oriented buffers, but the SurfaceTexture transform
                // already turns them upright for the phone's natural orientation — so the
                // frame WebRTC sees must carry the rotated size, while the buffer the camera
                // fills keeps the sensor's. setTextureSize sets both, synchronously, so the
                // buffer size is put back straight after.
                val size = request.resolution
                val quarterTurn = sensorRotation % 180 != 0
                helper.setTextureSize(
                    if (quarterTurn) size.height else size.width,
                    if (quarterTurn) size.width else size.height,
                )
                helper.surfaceTexture.setDefaultBufferSize(size.width, size.height)
                val target = Surface(helper.surfaceTexture)
                surface = target
                request.provideSurface(target, main) { it.surface.release() }
            }

            preview = useCase
            binder.register(
                this,
                selector,
                useCase,
                accept = { generation == bindGeneration },
                bindFirst = bindFirst,
            ) { bound ->
                if (generation != bindGeneration) return@register
                camera = bound
                watchCameraState(bound)
                applyFrameRate(bound)
                applyRememberedControls(bound)
                if (generation == bindGeneration) onBound?.invoke()
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun selectorFor(lens: LensEntry?): CameraSelector {
        if (lens == null) return CameraSelector.DEFAULT_BACK_CAMERA
        // CameraX's concurrent bind reads two selectors with equal lensFacing — null included —
        // as one logical camera and binds only one, so the facing must be set explicitly.
        return CameraSelector.Builder()
            .requireLensFacing(if (lens.front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == lens.cameraId } }
            .also { builder -> lens.physicalId?.let(builder::setPhysicalCameraId) }
            .build()
    }

    /**
     * The HAL's face detection, stabilisation and high-quality noise reduction keep a CPU
     * core busy around the clock on the S20U. Nothing here uses face metadata, the phone
     * does not move, and the encoder discards most of what the slow filters preserve.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun leaveOutWhatAFixedCameraDoesNotNeed(builder: Preview.Builder) {
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF,
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
    }

    /**
     * Idle rate while nothing is happening and nobody is watching: the sensor, the HAL and
     * both encoders all scale with frames delivered.
     */
    /** True when the sensor rate changed. A repeat of the current state does not. */
    fun setPowerSaving(saving: Boolean): Boolean {
        if (saving == powerSaving) return false
        powerSaving = saving
        reapplyFrameRate()
        return true
    }

    /**
     * Idle rate in any recording mode, including while a viewer is watching or a clip is
     * open. Power saving turns back off in those cases; throttling must not.
     */
    fun setThermalThrottle(on: Boolean) {
        if (on == thermalThrottle) return
        thermalThrottle = on
        reapplyFrameRate()
    }

    /** The rate asked of the sensor. [IDLE_FPS] while saving power or thermally throttled. */
    val targetFps: Int get() = if (powerSaving || thermalThrottle) IDLE_FPS else format.third

    private fun reapplyFrameRate() {
        ContextCompat.getMainExecutor(context).execute { camera?.let(::applyFrameRate) }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun applyFrameRate(bound: Camera) {
        val supported = Camera2CameraInfo.from(bound.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
        // The ceiling stays at the rate power saving or thermal throttle already asked for,
        // so a longer exposure cannot pull the sensor back up to the full rate.
        val ranges = supported.toList()
        val range = if (lowLight && compensationSupported(bound)) {
            lowLightRange(ranges, targetFps) ?: fixedRange(ranges, targetFps) ?: fixedRange(ranges, format.third)
        } else {
            fixedRange(ranges, targetFps) ?: fixedRange(ranges, format.third)
        } ?: return
        Camera2CameraControl.from(bound.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                .build()
        )
        applyExposure(bound)
    }

    private fun fixedRange(supported: List<Range<Int>>, fps: Int): Range<Int>? =
        supported.firstOrNull { it.lower == fps && it.upper == fps }

    /** Widest exposure at [fps]: the lowest floor the sensor offers that still caps at [fps]. */
    private fun lowLightRange(supported: List<Range<Int>>, fps: Int): Range<Int>? =
        supported.filter { it.upper == fps && it.lower >= LOW_LIGHT_MIN_FPS }.minByOrNull { it.lower }

    private fun compensationSupported(bound: Camera): Boolean {
        val state = bound.cameraInfo.exposureState
        return state.isExposureCompensationSupported && state.exposureCompensationStep.toFloat() > 0f
    }

    private fun applyExposure(bound: Camera) {
        val state = bound.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val index = if (lowLight) oneStopIndex(bound) ?: return else 0
        val clamped = index.coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
        if (state.exposureCompensationIndex == clamped) return
        bound.cameraControl.setExposureCompensationIndex(clamped)
    }

    private fun oneStopIndex(bound: Camera): Int? {
        val state = bound.cameraInfo.exposureState
        if (!compensationSupported(bound)) return null
        val step = state.exposureCompensationStep.toFloat()
        return (1f / step).roundToInt()
            .coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
    }

    private fun watchCameraState(bound: Camera) {
        unwatchCameraState()
        val source = bound.cameraInfo.cameraState
        stateSource = source
        source.observe(lifecycleOwner, stateObserver)
    }

    /** LiveData rejects removeObserver off the main thread, and a format change can arrive from any coroutine. */
    private fun unwatchCameraState() {
        val source = stateSource ?: return
        stateSource = null
        ContextCompat.getMainExecutor(context).execute { source.removeObserver(stateObserver) }
    }

    /**
     * Opens [next] by the means its catalog entry names; a switch is a rebind, about a second
     * without frames. [onBound] runs when this bind generation's camera is bound, not when an
     * older rebind catches up.
     */
    fun setLens(next: LensEntry, onBound: () -> Unit = {}) {
        if (next == lens) return
        lens = next
        rebind(onBound)
    }

    private fun rebind(onBound: () -> Unit) {
        val (width, height, framerate) = format
        stopCapture()
        openCapture(width, height, framerate, onBound)
    }

    fun setTorch(on: Boolean) {
        torchOn = on
        ContextCompat.getMainExecutor(context).execute { camera?.cameraControl?.enableTorch(on) }
    }

    /**
     * [ratio] is the position to restore on the next bind, or null when this lens has
     * none yet. Off leaves the live zoom alone; the next bind is what clears it.
     */
    fun setZoomLock(enabled: Boolean, ratio: Float?) {
        zoomLockEnabled = enabled
        zoomOnBind = if (enabled) ratio?.coerceIn(0f, 1f) else null
    }

    /**
     * Lowers the exposure floor and adds one stop, when this lens can compensate.
     * Off restores the fixed frame-rate range. Both ride [applyFrameRate], so an idle
     * or throttled rate keeps its ceiling.
     */
    fun setLowLight(enabled: Boolean) {
        if (enabled == lowLight) return
        lowLight = enabled
        reapplyFrameRate()
    }

    /**
     * [x] and [y] are 0..1 on the upright picture the viewer shows. [auto] returns to
     * continuous autofocus. The metering point is in the sensor buffer, so the point
     * is mapped back through the sensor rotation plus the orientation added to the frame.
     */
    fun setFocus(x: Float, y: Float, auto: Boolean, orientationDegrees: Int) {
        ContextCompat.getMainExecutor(context).execute {
            val control = camera?.cameraControl ?: return@execute
            if (auto) {
                control.cancelFocusAndMetering()
                return@execute
            }
            val (sx, sy) = FocusMap.toSensor(x, y, sensorRotationDegrees + orientationDegrees)
            val activePreview = preview
            val factory = if (activePreview != null) {
                SurfaceOrientedMeteringPointFactory(1f, 1f, activePreview)
            } else {
                SurfaceOrientedMeteringPointFactory(1f, 1f)
            }
            val action = FocusMeteringAction.Builder(
                factory.createPoint(sx, sy),
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
            ).disableAutoCancel().build()
            control.startFocusAndMetering(action)
        }
    }

    /**
     * [ratio] is a linear 0..1 position across the device's zoom range rather than an
     * absolute factor, because the range differs per lens and a viewer slider that means
     * something different on each phone is worse than no slider.
     */
    fun setZoom(ratio: Float) {
        val position = ratio.coerceIn(0f, 1f)
        userZoom = position
        if (zoomLockEnabled) zoomOnBind = position
        val active = lens
        ContextCompat.getMainExecutor(context).execute {
            if (active == null) camera?.cameraControl?.setLinearZoom(position)
            else camera?.cameraControl?.setZoomRatio(ZoomMap.factorAt(position, active.zoomMin, active.zoomMax))
        }
    }

    /** The slider position last asked for, if the viewer has set one since this bind. */
    internal fun zoomPosition(): Float? = userZoom

    internal fun linearZoom(): Float? = camera?.cameraInfo?.zoomState?.value?.linearZoom

    internal fun exposureCompensationIndex(): Int? = camera?.cameraInfo?.exposureState?.exposureCompensationIndex

    /** One extra stop in this lens's index, or null when compensation is not supported. */
    internal fun oneStopCompensationIndex(): Int? = camera?.let(::oneStopIndex)

    private fun applyRememberedControls(bound: Camera) {
        val active = lens
        val zoom = userZoom
        if (zoom == null) {
            active?.let { bound.cameraControl.setZoomRatio(it.zoomMin) }
        } else if (active == null) {
            bound.cameraControl.setLinearZoom(zoom)
        } else {
            bound.cameraControl.setZoomRatio(ZoomMap.factorAt(zoom, active.zoomMin, active.zoomMax))
        }
        if (torchOn) bound.cameraControl.enableTorch(true)
    }

    override fun stopCapture() {
        bindGeneration++
        unwatchCameraState()
        binder.unregister(this)
        camera = null
        preview = null
        helper?.stopListening()
        surface?.release()
        surface = null
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        stopCapture()
        openCapture(width, height, framerate, onBound = null)
    }

    override fun dispose() {
        stopCapture()
    }

    override fun isScreencast(): Boolean = false

    companion object {
        const val IDLE_FPS = 15

        /** AE floors below this are too dark a trade for a home camera that still has to see motion. */
        private const val LOW_LIGHT_MIN_FPS = 10

        fun errorName(code: Int): String = when (code) {
            CameraState.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
            CameraState.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "OTHER_RECOVERABLE_ERROR"
            CameraState.ERROR_STREAM_CONFIG -> "STREAM_CONFIG"
            CameraState.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
            CameraState.ERROR_CAMERA_FATAL_ERROR -> "CAMERA_FATAL_ERROR"
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "DO_NOT_DISTURB_MODE_ENABLED"
            else -> "UNKNOWN"
        }

        fun isHeldByAnotherApp(code: Int?): Boolean =
            code == CameraState.ERROR_CAMERA_IN_USE || code == CameraState.ERROR_MAX_CAMERAS_IN_USE
    }
}
