package com.homecam.core.diagnostics

/** Wire values for an `audio_listen` line. The feed is which capture was running. */
object AudioListen {
    const val TAP = "tap"
    const val LIVE = "live"

    const val MIC_OFF = "mic_off"
    const val SOUND_DETECTION_OFF = "sound_detection_off"
    const val VIEWER_LIVE = "viewer_live"
    const val SERVICE_STOP = "service_stop"

    /** A start while the microphone and sound detection are both on and no viewer is live. */
    const val ENABLED = "enabled"
}

/** Wire values for a `power_saving` reason. Engage is the idle tick; the others release. */
object PowerSavingReason {
    const val IDLE = "idle"
    const val VIEWER = "viewer"
    const val DETECTION = "detection"
    const val MOTION = "motion"
    const val SOUND = "sound"
    const val MANUAL_CLIP = "manual_clip"
}
