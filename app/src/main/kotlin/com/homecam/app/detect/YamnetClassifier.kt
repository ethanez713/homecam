package com.homecam.app.detect

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import com.homecam.core.sound.SoundClasses
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * YAMNet through the LiteRT interpreter (Phase 6.3), mirroring [ObjectDetector]: shapes are
 * read from the model at load time rather than hardcoded, so a model swapped underneath us
 * fails to load instead of producing confident nonsense.
 *
 * Input is a mono float waveform window; output is a score per AudioSet class. Only classes
 * that map to our small user-facing set (dog, alarm, …) are ever returned.
 */
class YamnetClassifier private constructor(
    private val interpreter: Interpreter,
    private val labels: List<String>,
    val windowSamples: Int,
    private val classCount: Int,
) {
    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(windowSamples * 4).order(ByteOrder.nativeOrder())
    private val scores = Array(1) { FloatArray(classCount) }

    data class Event(val category: String, val confidence: Float)

    /**
     * The strongest sound in [waveform] (16 kHz mono, [-1, 1]) above [threshold] whose
     * category is in [wanted], or null; a louder class outside [wanted] does not hide one inside. Shorter windows are zero-padded, longer ones truncated to what the model wants.
     */
    @Synchronized
    fun classify(waveform: FloatArray, wanted: Set<String>, threshold: Float = DEFAULT_THRESHOLD): Event? = try {
        input.rewind()
        for (i in 0 until windowSamples) input.putFloat(if (i < waveform.size) waveform[i] else 0f)
        input.rewind()
        interpreter.run(input, scores)

        var best: Event? = null
        for (i in 0 until classCount) {
            val category = labels.getOrNull(i)?.let { SoundClasses.categoryFor(it) }?.takeIf { it in wanted } ?: continue
            val score = scores[0][i]
            if (score >= threshold && (best == null || score > best.confidence)) {
                best = Event(category, score)
            }
        }
        best
    } catch (e: Exception) {
        Log.w(TAG, "yamnet rejected a window", e)
        null
    }

    fun close() = interpreter.close()

    companion object {
        private const val TAG = "YamnetClassifier"
        private const val MODEL_ASSET = "yamnet.tflite"
        private const val LABELS_ASSET = "yamnet_labels.txt"
        private const val DEFAULT_THRESHOLD = 0.3f

        /** Null when the model is missing or has an unexpected shape — sound then falls back to decibel only. */
        fun create(context: Context): YamnetClassifier? = try {
            val interpreter = Interpreter(loadModel(context), Interpreter.Options().apply { setNumThreads(1) })
            build(interpreter, readLabels(context))
        } catch (e: Exception) {
            Log.w(TAG, "no sound classifier; falling back to decibel only", e)
            null
        }

        private fun build(interpreter: Interpreter, labels: List<String>): YamnetClassifier? {
            val inputTensor = interpreter.getInputTensor(0)
            require(inputTensor.dataType() == DataType.FLOAT32) {
                "expected a float waveform input, got ${inputTensor.dataType()}"
            }
            val windowSamples = inputTensor.shape().fold(1) { acc, dim -> acc * dim }
            val outShape = interpreter.getOutputTensor(0).shape()
            val classCount = outShape.last()
            require(classCount == labels.size) {
                "model has $classCount classes but ${labels.size} labels shipped"
            }
            return YamnetClassifier(interpreter, labels, windowSamples, classCount)
        }

        private fun loadModel(context: Context): ByteBuffer {
            val descriptor: AssetFileDescriptor = context.assets.openFd(MODEL_ASSET)
            return FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }

        private fun readLabels(context: Context): List<String> =
            context.assets.open(LABELS_ASSET).bufferedReader().use { reader ->
                reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
    }
}
