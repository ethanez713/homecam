package com.homecam.app.camera

import com.homecam.proto.SessionConfig
import com.homecam.proto.SessionStatus

/**
 * What the SESSION service can ask of the media pipeline. Split out so [CameraServices]
 * does not have to know whether a camera is even attached — on a phone with no capture
 * running, these fail cleanly instead of being unimplemented.
 */
interface MediaController {
    suspend fun startSession(viewerFingerprint: String, config: SessionConfig): SessionStatus
    suspend fun stopSession(viewerFingerprint: String)
    suspend fun sessionStatus(viewerFingerprint: String): SessionStatus
    fun setTorch(on: Boolean)
    fun setZoom(ratio: Float)
    fun setPushToTalk(active: Boolean)
}
