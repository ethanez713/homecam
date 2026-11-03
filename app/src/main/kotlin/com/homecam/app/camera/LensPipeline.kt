package com.homecam.app.camera

import com.homecam.app.detect.DetectionPipeline
import com.homecam.app.media.ContinuousCapture
import com.homecam.core.lens.LensEntry

/** Capture, recording and detection for one lens. */
class LensPipeline(
    var lensId: String,
    val capture: ContinuousCapture,
    val recorder: Recorder,
    val detection: DetectionPipeline,
    var lens: LensEntry?,
    private val onDetach: () -> Unit,
) {
    fun stop() {
        capture.removeSink(detection)
        capture.stop()
        detection.close()
        recorder.stop()
        onDetach()
    }
}
