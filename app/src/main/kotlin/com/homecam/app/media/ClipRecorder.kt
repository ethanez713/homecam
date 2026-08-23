package com.homecam.app.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Encodes the capture to H.264 and writes fragmented MP4 clips.
 *
 * Frames arrive as WebRTC [VideoFrame]s and are drawn straight into MediaCodec's input
 * Surface through an [EglRenderer] sharing the process EGL context, so a texture-backed
 * frame reaches the encoder without ever being read back to the CPU.
 *
 * The encoder runs continuously; the *muxer* is what starts and stops. That is what makes
 * the pre-roll possible: encoded samples are held in [preRoll] until something asks for a
 * clip, and the clip then begins several seconds before the thing that triggered it —
 * which is where the useful part of a motion clip always is.
 */
class ClipRecorder(
    private val clipsDir: File,
    private val config: Config = Config(),
) : VideoSink {

    data class Config(
        val width: Int = 1280,
        val height: Int = 720,
        val framerate: Int = 24,
        val bitrate: Int = 3_000_000,
        val keyframeIntervalSeconds: Int = 2,
        val preRollMs: Long = 6_000,
        val maxClipMs: Long = 120_000,
    )

    data class Written(val id: String, val file: File, val startedAtMs: Long, val durationMs: Int)

    private val lock = Any()

    private var encoder: MediaCodec? = null
    private var renderer: EglRenderer? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1

    private var currentClip: File? = null
    private var currentId: String? = null
    private var clipStartedAtMs = 0L
    private var firstSampleUs = -1L
    private var lastSampleUs = 0L

    /**
     * Samples encoded before a clip was requested. Bounded by wall time rather than count,
     * because the frame rate is not constant.
     */
    private val preRoll = ArrayDeque<Sample>()
    private var formatForMuxer: MediaFormat? = null

    private class Sample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
        val keyframe: Boolean,
    )

    var onClipWritten: ((Written) -> Unit)? = null

    val recording: Boolean get() = synchronized(lock) { muxer != null }

    fun start(eglContext: EglBase.Context) {
        synchronized(lock) {
            if (encoder != null) return
            val format = MediaFormat.createVideoFormat(MIME, config.width, config.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.framerate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSeconds)
            }
            val codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec

            renderer = EglRenderer("homecam-recorder").apply {
                init(eglContext, EglBase.CONFIG_RECORDABLE, org.webrtc.GlRectDrawer())
                createEglSurface(surface)
            }
            Thread(::drain, "homecam-encoder-drain").apply { isDaemon = true }.start()
        }
    }

    override fun onFrame(frame: VideoFrame) {
        renderer?.onFrame(frame)
    }

    /** Opens a clip that begins [Config.preRollMs] before now. */
    fun beginClip(id: String, nowMs: Long) {
        synchronized(lock) {
            if (muxer != null) return
            val format = formatForMuxer ?: run {
                Log.w(TAG, "no encoded format yet; clip $id skipped")
                return
            }
            val file = File(clipsDir, "$id.mp4")
            file.parentFile?.mkdirs()

            val output = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrackIndex = output.addTrack(format)
            output.start()

            muxer = output
            currentClip = file
            currentId = id
            firstSampleUs = -1

            // A clip that starts on a mid-GOP frame is undecodable until the next
            // keyframe, so the pre-roll is trimmed to begin at one.
            val fromKeyframe = preRoll.dropWhile { !it.keyframe }
            clipStartedAtMs = nowMs - (fromKeyframe.firstOrNull()?.let {
                (preRoll.last().presentationTimeUs - it.presentationTimeUs) / 1000
            } ?: 0L)
            fromKeyframe.forEach { writeSample(output, it) }
            preRoll.clear()
        }
    }

    fun endClip(): Written? = synchronized(lock) {
        val output = muxer ?: return null
        val file = currentClip
        val id = currentId
        muxer = null
        currentClip = null
        currentId = null

        runCatching { output.stop() }.onFailure { Log.w(TAG, "muxer stop", it) }
        runCatching { output.release() }

        if (file == null || id == null) return null
        val duration = ((lastSampleUs - firstSampleUs) / 1000).toInt().coerceAtLeast(0)
        Written(id, file, clipStartedAtMs, duration).also { onClipWritten?.invoke(it) }
    }

    fun stop() {
        endClip()
        synchronized(lock) {
            runCatching { renderer?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            renderer = null
            encoder = null
            preRoll.clear()
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val codec = synchronized(lock) { encoder } ?: return
            val index = try {
                codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                return
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    synchronized(lock) { formatForMuxer = codec.outputFormat }

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        handleSample(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun handleSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(bytes)
        val sample = Sample(
            data = bytes,
            presentationTimeUs = info.presentationTimeUs,
            flags = info.flags,
            keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
        )

        synchronized(lock) {
            val output = muxer
            if (output != null) {
                writeSample(output, sample)
                if (lastSampleUs - firstSampleUs > config.maxClipMs * 1000) endClipLocked()
                return
            }
            preRoll.addLast(sample)
            while (preRoll.size > 1 &&
                preRoll.last().presentationTimeUs - preRoll.first().presentationTimeUs >
                config.preRollMs * 1000
            ) {
                preRoll.removeFirst()
            }
        }
    }

    private fun writeSample(output: MediaMuxer, sample: Sample) {
        if (firstSampleUs < 0) firstSampleUs = sample.presentationTimeUs
        lastSampleUs = sample.presentationTimeUs
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.data.size, sample.presentationTimeUs - firstSampleUs, sample.flags)
        }
        runCatching {
            output.writeSampleData(videoTrackIndex, ByteBuffer.wrap(sample.data), info)
        }.onFailure { Log.w(TAG, "sample dropped", it) }
    }

    private fun endClipLocked() {
        val output = muxer ?: return
        val file = currentClip
        val id = currentId
        muxer = null
        currentClip = null
        currentId = null
        runCatching { output.stop(); output.release() }
        if (file != null && id != null) {
            val duration = ((lastSampleUs - firstSampleUs) / 1000).toInt().coerceAtLeast(0)
            onClipWritten?.invoke(Written(id, file, clipStartedAtMs, duration))
        }
    }

    private companion object {
        const val TAG = "ClipRecorder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DRAIN_TIMEOUT_US = 100_000L
    }
}
