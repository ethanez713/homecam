package com.homecam.app.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.homecam.core.detect.Detection
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector as TfObjectDetector

/**
 * EfficientDet-Lite0 over the Task Library, which reads the label map and normalisation
 * out of the model's own metadata — so the class list cannot drift out of sync with the
 * file the way a hardcoded array would.
 *
 * The GPU delegate is not used. Detection runs a handful of times a second behind a motion
 * gate, so the delegate's setup cost and its habit of holding the GPU awake would buy
 * latency this pipeline does not need and spend power it cannot spare (§9).
 */
class ObjectDetector private constructor(private val delegate: TfObjectDetector) {

    fun detect(bitmap: Bitmap): List<Detection> = try {
        delegate.detect(TensorImage.fromBitmap(bitmap)).flatMap { result ->
            val box = result.boundingBox
            result.categories.take(1).map { category ->
                Detection(
                    label = category.label,
                    confidence = category.score,
                    left = box.left / bitmap.width,
                    top = box.top / bitmap.height,
                    right = box.right / bitmap.width,
                    bottom = box.bottom / bitmap.height,
                )
            }
        }
    } catch (e: IllegalStateException) {
        Log.w(TAG, "detector rejected a frame", e)
        emptyList()
    }

    fun close() = delegate.close()

    companion object {
        private const val TAG = "ObjectDetector"
        private const val ASSET = "efficientdet_lite0.tflite"

        /**
         * Null when the model is missing or unloadable. The camera then falls back to
         * motion-only recording rather than refusing to record, because a camera that
         * stops working when a classifier does is worse than one that labels less.
         */
        fun create(context: Context, maxResults: Int = 10, threshold: Float = 0.3f): ObjectDetector? =
            try {
                ObjectDetector(
                    TfObjectDetector.createFromFileAndOptions(
                        context,
                        ASSET,
                        TfObjectDetector.ObjectDetectorOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
                            .setMaxResults(maxResults)
                            .setScoreThreshold(threshold)
                            .build(),
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "no object detector; falling back to motion-only", e)
                null
            }
    }
}
