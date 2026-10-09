package com.homecam.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.homecam.core.lens.ConcurrencyVerdict
import com.homecam.core.lens.StreamTrial
import com.homecam.core.lens.Trial
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan

/**
 * Measures what the camera hardware can actually do at once (Phase 12.4). Whether two
 * lenses can run together is a property of the phone's camera HAL and encoder budget, so
 * the answer comes from running them: every set the platform claims is concurrent, and
 * every pair of physical lenses inside a logical camera, is bound with an encoder per
 * stream for [trialSeconds], and [ConcurrencyVerdict] reads what each stream delivered.
 *
 * Needs the camera to itself: stop the camera service first.
 */
class LensProbe(
    private val context: Context,
    private val trialSeconds: Int = 120,
    private val sizes: List<Size> = listOf(Size(1280, 720), Size(1920, 1080)),
) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** One stream of a set: a camera, and the physical lens inside it when the set is one logical camera's. */
    private data class Member(val cameraId: String, val physicalId: String?)

    private data class Candidate(val kind: String, val members: List<Member>)

    fun run(): JSONObject {
        val candidates = candidates()
        val sets = JSONArray()
        candidates.forEach { candidate ->
            val trials = sizes.map { runTrial(candidate.members, it) }
            sets.put(
                JSONObject()
                    .put("kind", candidate.kind)
                    .put("members", JSONArray(candidate.members.map { it.label() }))
                    .put("trials", JSONArray(trials.map(::trialJson)))
                    .put("best", ConcurrencyVerdict.best(trials)?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL)
                    .put("feasible", ConcurrencyVerdict.best(trials) != null),
            )
        }
        return survey()
            .put("trial_seconds", trialSeconds)
            .put("sets", sets)
            .put("any_feasible", (0 until sets.length()).any { sets.getJSONObject(it).getBoolean("feasible") })
    }

    fun survey(): JSONObject = JSONObject()
        .put("cameras", JSONArray(cameraIds().mapNotNull(::cameraJson)))
        .put("platform_concurrent_ids", JSONArray(claimedSets().map { JSONArray(it.sorted()) }))
        .put("camerax_concurrent_ids", JSONArray(cameraXConcurrentSets().map { JSONArray(it.sorted()) }))
        .put("encoders", JSONArray(listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC).flatMap(::encoderJson)))
        .put("battery_temp_c", batteryTempC() ?: JSONObject.NULL)

    private fun cameraIds(): List<String> = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())

    private fun claimedSets(): Set<Set<String>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { manager.concurrentCameraIds }.getOrDefault(emptySet())
        else emptySet()

    @SuppressLint("UnsafeOptInUsageError")
    private fun cameraXConcurrentSets(): List<Set<String>> = runCatching {
        val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get(5, TimeUnit.SECONDS)
        provider.availableConcurrentCameraInfos.map { infos ->
            infos.map { androidx.camera.camera2.interop.Camera2CameraInfo.from(it).cameraId }.toSet()
        }
    }.getOrDefault(emptyList())

    private fun candidates(): List<Candidate> {
        val concurrent = claimedSets().filter { it.size >= 2 }.map { ids ->
            Candidate("concurrent", ids.sorted().map { Member(it, null) })
        }
        val logical = cameraIds().flatMap { id ->
            val physical = physicalIds(id)
            if (physical.size < 2) emptyList()
            else physical.flatMapIndexed { i, a -> physical.drop(i + 1).map { b -> Candidate("logical", listOf(Member(id, a), Member(id, b))) } }
        }
        return (concurrent + logical).distinct().take(MAX_CANDIDATES)
    }

    private fun physicalIds(id: String): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { manager.getCameraCharacteristics(id).physicalCameraIds.toList().sorted() }.getOrDefault(emptyList())
        } else emptyList()

    private fun cameraJson(id: String): JSONObject? {
        val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return null
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
        val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        return JSONObject()
            .put("id", id)
            .put("facing", c.get(CameraCharacteristics.LENS_FACING)?.let { if (it == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back" } ?: "other")
            .put("fov_deg", if (focal != null && sensor != null) Math.toDegrees(2 * atan((sensor / (2 * focal)).toDouble())).toInt() else JSONObject.NULL)
            .put("physical_ids", JSONArray(physicalIds(id)))
            .put("zoom_ratio", zoom?.let { "${it.lower}..${it.upper}" } ?: JSONObject.NULL)
    }

    private fun encoderJson(mime: String): List<JSONObject> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
            .filter { Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || it.isHardwareAccelerated }
            .map { info ->
                val caps = info.getCapabilitiesForType(mime)
                JSONObject()
                    .put("mime", mime)
                    .put("name", info.name)
                    .put("max_instances", caps.maxSupportedInstances)
                    .put("performance_points", performancePoints(caps))
            }

    private fun performancePoints(caps: MediaCodecInfo.CodecCapabilities): JSONArray =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            JSONArray(caps.videoCapabilities?.supportedPerformancePoints?.map { it.toString() }.orEmpty())
        } else JSONArray()

    private fun trialJson(trial: Trial): JSONObject = JSONObject()
        .put("size", "${trial.width}x${trial.height}")
        .put("seconds", trial.seconds)
        .put("ran_ms", trial.ranMs)
        .put("temp_rise_c", trial.tempRiseC ?: JSONObject.NULL)
        .put("open_error", trial.openError ?: JSONObject.NULL)
        .put("feasible", ConcurrencyVerdict.feasible(trial))
        .put(
            "streams",
            JSONArray(
                trial.streams.map {
                    JSONObject()
                        .put("camera", it.cameraId)
                        .put("physical", it.physicalId ?: JSONObject.NULL)
                        .put("window_fps", JSONArray(it.windowFps.map { fps -> Math.round(fps * 10) / 10.0 }))
                        .put("error", it.error ?: JSONObject.NULL)
                },
            ),
        )

    private fun Member.label() = if (physicalId == null) cameraId else "$cameraId/$physicalId"

    private class Stream(val member: Member, val codec: MediaCodec, val surface: Surface) {
        val frames = AtomicLong()
        val error = AtomicReference<String?>(null)
        val windowFps = mutableListOf<Float>()
        @Volatile var draining = true
        lateinit var drain: Thread
    }

    @SuppressLint("MissingPermission")
    private fun runTrial(members: List<Member>, size: Size): Trial {
        val temperatureBefore = batteryTempC()
        val streams = mutableListOf<Stream>()
        val devices = mutableListOf<CameraDevice>()
        val sessions = mutableListOf<CameraCaptureSession>()
        val thread = HandlerThread("lens-probe").apply { start() }
        val handler = Handler(thread.looper)
        var openError: String? = null
        var ranMs = 0L
        try {
            val unsupported = members.firstOrNull { !supports(it.cameraId, size) }
            if (unsupported != null) {
                openError = "camera ${unsupported.cameraId} cannot encode ${size.width}x${size.height}"
            } else {
                members.forEach { streams += openStream(it, size) }
                for ((cameraId, group) in streams.groupBy { it.member.cameraId }) {
                    val device = open(cameraId, handler) ?: throw IllegalStateException("camera $cameraId would not open")
                    devices += device
                    sessions += startSession(device, group, handler)
                }
                ranMs = sample(streams)
            }
        } catch (e: Exception) {
            openError = e.message ?: e.javaClass.simpleName
        } finally {
            sessions.forEach { runCatching { it.stopRepeating() }; runCatching { it.close() } }
            devices.forEach { runCatching { it.close() } }
            streams.forEach { stream ->
                stream.draining = false
                runCatching { stream.drain.join(DRAIN_JOIN_MS) }
                runCatching { stream.codec.stop() }
                runCatching { stream.codec.release() }
                runCatching { stream.surface.release() }
            }
            thread.quitSafely()
        }
        val rise = temperatureBefore?.let { before -> batteryTempC()?.minus(before) }
        return Trial(
            width = size.width,
            height = size.height,
            seconds = trialSeconds,
            ranMs = ranMs,
            streams = streams.map { StreamTrial(it.member.cameraId, it.member.physicalId, it.windowFps.toList(), it.error.get()) },
            tempRiseC = rise,
            openError = openError,
        )
    }

    private fun supports(cameraId: String, size: Size): Boolean = runCatching {
        manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaCodec::class.java)
            ?.any { it.width == size.width && it.height == size.height } ?: false
    }.getOrDefault(false)

    private fun openStream(member: Member, size: Size): Stream {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (size.width >= 1920) 8_000_000 else 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val stream = Stream(member, codec, codec.createInputSurface())
        codec.start()
        stream.drain = Thread {
            val info = MediaCodec.BufferInfo()
            while (stream.draining) {
                val index = runCatching { codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US) }.getOrDefault(-1)
                if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) stream.frames.incrementAndGet()
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }
        }.apply { name = "lens-probe-drain-${member.label()}"; start() }
        return stream
    }

    @SuppressLint("MissingPermission")
    private fun open(cameraId: String, handler: Handler): CameraDevice? {
        val opened = CountDownLatch(1)
        var device: CameraDevice? = null
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { device = camera; opened.countDown() }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); opened.countDown() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); opened.countDown() }
        }, handler)
        opened.await(OPEN_TIMEOUT_S, TimeUnit.SECONDS)
        return device
    }

    @Suppress("DEPRECATION")
    private fun startSession(device: CameraDevice, group: List<Stream>, handler: Handler): CameraCaptureSession {
        val ready = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; ready.countDown() }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                group.forEach { it.error.compareAndSet(null, "session configuration failed") }
                ready.countDown()
            }
        }
        val usesPhysical = group.any { it.member.physicalId != null }
        if (usesPhysical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputs = group.map { stream ->
                OutputConfiguration(stream.surface).also { it.setPhysicalCameraId(stream.member.physicalId) }
            }
            device.createCaptureSessionByOutputConfigurations(outputs, callback, handler)
        } else {
            device.createCaptureSession(group.map { it.surface }, callback, handler)
        }
        ready.await(OPEN_TIMEOUT_S, TimeUnit.SECONDS)
        val configured = session ?: throw IllegalStateException(group.firstNotNullOfOrNull { it.error.get() } ?: "session did not configure")
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            group.forEach { addTarget(it.surface) }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(TARGET_FPS, TARGET_FPS))
        }
        try {
            configured.setRepeatingRequest(request.build(), null, handler)
        } catch (e: CameraAccessException) {
            throw IllegalStateException("repeating request refused: ${e.message}")
        }
        return configured
    }

    /** Returns how long the streams were watched; stops early as soon as any stream reports an error. */
    private fun sample(streams: List<Stream>): Long {
        val started = System.currentTimeMillis()
        val windowMs = ConcurrencyVerdict.WINDOW_SECONDS * 1000L
        val last = LongArray(streams.size)
        var windowStart = started
        for (window in 1..trialSeconds / ConcurrencyVerdict.WINDOW_SECONDS) {
            val closesAt = started + window * windowMs
            while (System.currentTimeMillis() < closesAt) {
                if (streams.any { it.error.get() != null }) return System.currentTimeMillis() - started
                Thread.sleep(minOf(SAMPLE_MS, closesAt - System.currentTimeMillis()).coerceAtLeast(1))
            }
            val now = System.currentTimeMillis()
            streams.forEachIndexed { i, stream ->
                val total = stream.frames.get()
                stream.windowFps += (total - last[i]) * 1000f / (now - windowStart)
                last[i] = total
            }
            windowStart = now
        }
        return System.currentTimeMillis() - started
    }

    private fun batteryTempC(): Float? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.div(10f)

    private companion object {
        const val TARGET_FPS = 30
        const val MAX_CANDIDATES = 8
        const val OPEN_TIMEOUT_S = 5L
        const val DRAIN_JOIN_MS = 1_000L
        const val DEQUEUE_TIMEOUT_US = 50_000L
        const val SAMPLE_MS = 500L
    }
}
