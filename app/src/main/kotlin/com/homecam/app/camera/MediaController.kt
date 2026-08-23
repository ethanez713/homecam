package com.homecam.app.camera

import com.homecam.proto.Lens
import com.homecam.proto.PrivacyMask
import com.homecam.proto.Resolution
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
    fun setPrivacyMask(mask: PrivacyMask)
    suspend fun setResolution(resolution: Resolution): SessionStatus
    fun setLens(facing: Lens.Facing)
}
