package com.homecam.app.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.MuxerUtil
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Encodes the capture to HEVC where the phone has a hardware encoder for it, H.264
 * otherwise, and writes fragmented MP4 clips.
 *
 * The codec choice is the recorder's alone. The live path stays on H.264 for interop and
 * because congestion control drives its bitrate anyway (`DECISIONS.md` D6); the recorder
 * is writing a file that has to be good months later, and HEVC reaches the same quality at
 * roughly 60% of the bitrate — which is 40% more days of history inside the same
 * `maxSizeGb` ceiling, on a phone whose storage is the binding constraint.
 *
 * Fragmented, and written by media3's muxer rather than [android.media.MediaMuxer], for
 * one reason: `MediaMuxer` only writes the `moov` index when the file is stopped, so a
 * camera phone that loses power or gets killed mid-clip leaves a file with no index and
 * therefore no clip at all. A fragmented file carries its index up front and is complete
 * at every fragment boundary, so the same interruption costs at most the last
 * [Config.keyframeIntervalSeconds] seconds. That is the difference between losing a few
 * frames and losing the recording that mattered.
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
@UnstableApi
class ClipRecorder(
    private val clipsDir: File,
    private val config: Config = Config(),
) : VideoSink {

    data class Config(
        val width: Int = 1920,
        val height: Int = 1080,
        val framerate: Int = 30,
        val bitrate: Int = 6_000_000,
        val keyframeIntervalSeconds: Int = 2,
        /** For H.264. HEVC is given [HEVC_BITRATE_RATIO] of it for the same quality. */
        val preRollMs: Long = 6_000,
        val maxClipMs: Long = 120_000,
    )

    data class Written(val id: String, val file: File, val startedAtMs: Long, val durationMs: Int)

    private val lock = Any()

    private val mime = if (hasHardwareEncoder(HEVC, config.width, config.height)) HEVC else AVC
    private val bitrate =
        if (mime == HEVC) config.bitrate * HEVC_BITRATE_RATIO / 100 else config.bitrate

    private var encoder: MediaCodec? = null
    private var renderer: EglRenderer? = null
    private var muxer: FragmentedMp4Muxer? = null
    private var muxerOutput: FileOutputStream? = null
    private var videoTrackIndex = -1
    private var metadataTrackIndex = -1

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

    val currentClipId: String? get() = synchronized(lock) { currentId }

    private var eglContext: EglBase.Context? = null
    private var encodingPortrait = false

    fun start(eglContext: EglBase.Context) {
        synchronized(lock) {
            if (encoder != null) return
            this.eglContext = eglContext
            startEncoderLocked(portrait = false)
        }
    }

    private fun startEncoderLocked(portrait: Boolean) {
        val context = eglContext ?: return
        val (long, short) = maxOf(config.width, config.height) to minOf(config.width, config.height)
        val format = MediaFormat.createVideoFormat(
            mime, if (portrait) short else long, if (portrait) long else short,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.framerate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSeconds)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        encoder = codec
        encodingPortrait = portrait

        renderer = EglRenderer("homecam-recorder").apply {
            init(context, EglBase.CONFIG_RECORDABLE, org.webrtc.GlRectDrawer())
            createEglSurface(surface)
        }
        Thread({ drain(codec) }, "homecam-encoder-drain").apply { isDaemon = true }.start()
    }

    /**
     * The renderer fills the encoder's surface edge to edge, so a portrait frame drawn into
     * a landscape encoder comes out squashed. The encoder follows the frame instead —
     * which also covers a viewer rotating the camera mid-run.
     */
    private fun matchOrientation(frame: VideoFrame) {
        val portrait = frame.rotatedHeight > frame.rotatedWidth
        if (portrait == encodingPortrait) return
        synchronized(lock) {
            if (encoder == null || portrait == encodingPortrait) return
            endClipLocked()
            releaseEncoderLocked()
            preRoll.clear()
            formatForMuxer = null
            startEncoderLocked(portrait)
        }
    }

    private fun releaseEncoderLocked() {
        runCatching { renderer?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        renderer = null
        encoder = null
    }

    override fun onFrame(frame: VideoFrame) {
        matchOrientation(frame)
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

            val stream = FileOutputStream(file)
            val output = FragmentedMp4Muxer.Builder(stream.channel)
                // A fragment that does not start on a keyframe is not independently
                // decodable, so there is nothing to gain from a boundary between IDRs.
                .setFragmentDurationMs(config.keyframeIntervalSeconds * 1000L)
                // Every sample handed over is a freshly allocated array that nothing
                // writes to again, so the muxer's defensive copy is pure cost.
                .setSampleCopyingEnabled(false)
                .build()
            videoTrackIndex = output.addTrack(MediaFormatUtil.createFormatFromMediaFormat(format))
            metadataTrackIndex = addMetadataTrack(output)

            muxer = output
            muxerOutput = stream
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

    /**
     * The muxer writes the `mett` sample entry itself, so unlike `MediaMuxer` — which only
     * accepted timed metadata from API 29 — this works on every device the app runs on and
     * the clip is self-describing everywhere.
     */
    private fun addMetadataTrack(output: FragmentedMp4Muxer): Int =
        runCatching {
            output.addTrack(Format.Builder().setSampleMimeType(METADATA_MIME).build())
        }.getOrElse {
            Log.w(TAG, "no timed-metadata track on this device", it)
            -1
        }

    /** Written against the clip's own timeline, so the boxes line up on playback. */
    fun writeDetections(payload: ByteArray, atMs: Long) {
        synchronized(lock) {
            val output = muxer ?: return
            if (metadataTrackIndex < 0) return
            val offsetUs = ((atMs - clipStartedAtMs) * 1000).coerceAtLeast(0)
            val info = MediaCodec.BufferInfo().apply { set(0, payload.size, offsetUs, 0) }
            runCatching {
                output.writeSampleData(
                    metadataTrackIndex,
                    ByteBuffer.wrap(payload),
                    MuxerUtil.getMuxerBufferInfoFromMediaCodecBufferInfo(info),
                )
            }.onFailure { Log.w(TAG, "metadata sample dropped", it) }
        }
    }

    fun endClip(): Written? = synchronized(lock) {
        val output = muxer ?: return null
        val stream = muxerOutput
        val file = currentClip
        val id = currentId
        muxer = null
        muxerOutput = null
        currentClip = null
        currentId = null

        runCatching { output.close() }.onFailure { Log.w(TAG, "muxer close", it) }
        runCatching { stream?.close() }

        if (file == null || id == null) return null
        val duration = ((lastSampleUs - firstSampleUs) / 1000).toInt().coerceAtLeast(0)
        Written(id, file, clipStartedAtMs, duration).also { onClipWritten?.invoke(it) }
    }

    fun stop() {
        endClip()
        synchronized(lock) {
            releaseEncoderLocked()
            preRoll.clear()
        }
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (synchronized(lock) { encoder } !== codec) return
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

    private fun writeSample(output: FragmentedMp4Muxer, sample: Sample) {
        if (firstSampleUs < 0) firstSampleUs = sample.presentationTimeUs
        lastSampleUs = sample.presentationTimeUs
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.data.size, sample.presentationTimeUs - firstSampleUs, sample.flags)
        }
        runCatching {
            output.writeSampleData(
                videoTrackIndex,
                ByteBuffer.wrap(sample.data),
                MuxerUtil.getMuxerBufferInfoFromMediaCodecBufferInfo(info),
            )
        }.onFailure { Log.w(TAG, "sample dropped", it) }
    }

    private fun endClipLocked() {
        val output = muxer ?: return
        val stream = muxerOutput
        val file = currentClip
        val id = currentId
        muxer = null
        muxerOutput = null
        currentClip = null
        currentId = null
        runCatching { output.close(); stream?.close() }
        if (file != null && id != null) {
            val duration = ((lastSampleUs - firstSampleUs) / 1000).toInt().coerceAtLeast(0)
            onClipWritten?.invoke(Written(id, file, clipStartedAtMs, duration))
        }
    }

    private companion object {
        const val TAG = "ClipRecorder"
        const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        const val HEVC_BITRATE_RATIO = 60
        const val METADATA_MIME = "application/x-homecam-detections"
        const val DRAIN_TIMEOUT_US = 100_000L

        /**
         * A software HEVC encoder would cost more battery and heat than the bitrate saves,
         * which is the whole reason for the choice, so only a hardware one counts.
         */
        private fun hasHardwareEncoder(mime: String, width: Int, height: Int): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && !info.isSoftwareOnlyCompat() &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    runCatching {
                        info.getCapabilitiesForType(mime)
                            .videoCapabilities?.isSizeSupported(width, height) == true
                    }.getOrDefault(false)
            }

        /** `isSoftwareOnly` arrived at API 29; below it the names are the only signal. */
        private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isSoftwareOnly
            else name.startsWith("OMX.google.") || name.startsWith("c2.android.")
    }
}
