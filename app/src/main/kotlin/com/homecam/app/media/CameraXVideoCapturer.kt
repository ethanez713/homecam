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
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
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
    initialLensFacing: Int = CameraSelector.LENS_FACING_BACK,
) : VideoCapturer {

    private var lensFacing = initialLensFacing
    private var format = Triple(1920, 1080, 30)
    @Volatile private var powerSaving = false

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var surface: Surface? = null
    private var camera: Camera? = null
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
        format = Triple(width, height, framerate)
        val helper = helper ?: return
        val observer = observer ?: return

        helper.startListening { frame -> observer.onFrameCaptured(frame) }
        observer.onCapturerStarted(true)

        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider

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

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val sensorRotation = selector.filter(cameraProvider.availableCameraInfos)
                .firstOrNull()?.sensorRotationDegrees ?: 0

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
            cameraProvider.unbindAll()
            val bound = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                useCase,
            )
            camera = bound
            watchCameraState(bound)
            applyFrameRate(bound)
        }, main)
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
     * Half rate while nothing is happening and nobody is watching: the sensor, the HAL and
     * both encoders all scale with frames delivered.
     */
    fun setPowerSaving(saving: Boolean) {
        if (saving == powerSaving) return
        powerSaving = saving
        ContextCompat.getMainExecutor(context).execute { camera?.let(::applyFrameRate) }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun applyFrameRate(bound: Camera) {
        val supported = Camera2CameraInfo.from(bound.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
        val fixed = { fps: Int -> supported.firstOrNull { it.lower == fps && it.upper == fps } }
        val range = (if (powerSaving) fixed(POWER_SAVING_FPS) else null) ?: fixed(format.third) ?: return
        Camera2CameraControl.from(bound.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                .build()
        )
    }

    private fun watchCameraState(bound: Camera) {
        unwatchCameraState()
        val source = bound.cameraInfo.cameraState
        stateSource = source
        source.observe(lifecycleOwner, stateObserver)
    }

    private fun unwatchCameraState() {
        stateSource?.removeObserver(stateObserver)
        stateSource = null
    }

    fun setLensFacing(front: Boolean) {
        val next = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        if (next == lensFacing) return
        lensFacing = next
        val (width, height, framerate) = format
        changeCaptureFormat(width, height, framerate)
    }

    fun setTorch(on: Boolean) {
        ContextCompat.getMainExecutor(context).execute { camera?.cameraControl?.enableTorch(on) }
    }

    /**
     * [ratio] is a linear 0..1 position across the device's zoom range rather than an
     * absolute factor, because the range differs per lens and a viewer slider that means
     * something different on each phone is worse than no slider.
     */
    fun setZoom(ratio: Float) {
        ContextCompat.getMainExecutor(context).execute {
            camera?.cameraControl?.setLinearZoom(ratio.coerceIn(0f, 1f))
        }
    }

    override fun stopCapture() {
        unwatchCameraState()
        ContextCompat.getMainExecutor(context).execute {
            preview?.let { provider?.unbind(it) }
            preview = null
            camera = null
        }
        helper?.stopListening()
        surface?.release()
        surface = null
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        stopCapture()
        startCapture(width, height, framerate)
    }

    override fun dispose() {
        stopCapture()
        provider = null
    }

    override fun isScreencast(): Boolean = false

    companion object {
        private const val POWER_SAVING_FPS = 15

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
