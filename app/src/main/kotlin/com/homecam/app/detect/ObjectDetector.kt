package com.homecam.app.detect

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.util.Log
import com.homecam.core.detect.Detection
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * EfficientDet-Lite0 driven straight through the LiteRT interpreter.
 *
 * The model ends in a `TFLite_Detection_PostProcess` op, so decoding and non-maximum
 * suppression already happened inside the graph: the four output tensors are the final
 * answer, and there is no postprocessing to get wrong here.
 *
 * This used to go through the TFLite Task Library, which read the label map out of the
 * model's own metadata. That library's last release predates the 16 KB page size
 * requirement and it is no longer maintained, so the labels now ship beside the model as
 * a reviewable asset instead — see `models/README.md`.
 */
class ObjectDetector private constructor(
    private val interpreter: Interpreter,
    private val labels: List<String>,
    private val inputSize: Int,
    private val maxDetections: Int,
    private val quantizedInput: Boolean,
    private val threshold: Float,
) {
    private val boxes = Array(1) { Array(maxDetections) { FloatArray(4) } }
    private val classes = Array(1) { FloatArray(maxDetections) }
    private val scores = Array(1) { FloatArray(maxDetections) }
    private val count = FloatArray(1)

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * 3 * if (quantizedInput) 1 else 4)
        .order(ByteOrder.nativeOrder())

    private val pixels = IntArray(inputSize * inputSize)

    @Synchronized
    fun detect(bitmap: Bitmap): List<Detection> = try {
        fillInput(bitmap)
        interpreter.runForMultipleInputsOutputs(
            arrayOf<Any>(inputBuffer),
            mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count),
        )
        readDetections()
    } catch (e: Exception) {
        Log.w(TAG, "detector rejected a frame", e)
        emptyList()
    }

    private fun fillInput(bitmap: Bitmap) {
        val scaled = if (bitmap.width == inputSize && bitmap.height == inputSize) bitmap
        else Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        if (scaled !== bitmap) scaled.recycle()

        inputBuffer.rewind()
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (quantizedInput) {
                inputBuffer.put(r.toByte()); inputBuffer.put(g.toByte()); inputBuffer.put(b.toByte())
            } else {
                inputBuffer.putFloat(r / 255f); inputBuffer.putFloat(g / 255f); inputBuffer.putFloat(b / 255f)
            }
        }
        inputBuffer.rewind()
    }

    private fun readDetections(): List<Detection> {
        val found = count[0].toInt().coerceIn(0, maxDetections)
        val out = ArrayList<Detection>(found)
        for (i in 0 until found) {
            val score = scores[0][i]
            if (score < threshold) continue
            val label = labels.getOrNull(classes[0][i].toInt()) ?: continue
            // The op emits boxes as (ymin, xmin, ymax, xmax), normalised — not the
            // left/top/right/bottom order everything downstream uses.
            val box = boxes[0][i]
            out += Detection(
                label = label,
                confidence = score,
                left = box[1].coerceIn(0f, 1f),
                top = box[0].coerceIn(0f, 1f),
                right = box[3].coerceIn(0f, 1f),
                bottom = box[2].coerceIn(0f, 1f),
            )
        }
        return out
    }

    fun close() = interpreter.close()

    companion object {
        private const val TAG = "ObjectDetector"
        private const val MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val LABELS_ASSET = "labelmap.txt"

        /**
         * Null when the model is missing or does not have the shape this code expects.
         * The camera then falls back to motion-only recording rather than refusing to
         * record, because a camera that stops working when a classifier does is worse
         * than one that labels less.
         */
        fun create(context: Context, threshold: Float = 0.3f): ObjectDetector? = try {
            val interpreter = Interpreter(
                loadModel(context),
                Interpreter.Options().apply { setNumThreads(2) },
            )
            build(interpreter, readLabels(context), threshold)
        } catch (e: Exception) {
            Log.w(TAG, "no object detector; falling back to motion-only", e)
            null
        }

        /**
         * The output layout is a contract of the `TFLite_Detection_PostProcess` op rather
         * than of this particular file, but a model swapped underneath us would otherwise
         * produce confident nonsense, so it is checked rather than assumed.
         */
        private fun build(
            interpreter: Interpreter,
            labels: List<String>,
            threshold: Float,
        ): ObjectDetector? {
            val input = interpreter.getInputTensor(0)
            val inputShape = input.shape()
            require(inputShape.size == 4 && inputShape[1] == inputShape[2] && inputShape[3] == 3) {
                "unexpected input shape ${inputShape.toList()}"
            }
            require(interpreter.outputTensorCount == 4) {
                "expected 4 outputs, found ${interpreter.outputTensorCount}"
            }
            val boxShape = interpreter.getOutputTensor(0).shape()
            require(boxShape.size == 3 && boxShape[2] == 4) {
                "output 0 is not a box tensor: ${boxShape.toList()}"
            }
            return ObjectDetector(
                interpreter = interpreter,
                labels = labels,
                inputSize = inputShape[1],
                maxDetections = boxShape[1],
                quantizedInput = input.dataType() == org.tensorflow.lite.DataType.UINT8,
                threshold = threshold,
            )
        }

        /**
         * Mapped rather than read: the model is 4.5 MB and mapping keeps it out of the
         * heap, which is why the build is told not to compress `.tflite`.
         */
        private fun loadModel(context: Context): ByteBuffer {
            val descriptor: AssetFileDescriptor = context.assets.openFd(MODEL_ASSET)
            return FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }

        private fun readLabels(context: Context): List<String> =
            context.assets.open(LABELS_ASSET).bufferedReader().use { reader ->
                reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
    }
}
