package com.homecam.core.rpc

import com.homecam.proto.Capabilities

/**
 * Turns a camera's advertised [Capabilities] into which optional viewer controls should be
 * offered. The dispatcher already makes an unsupported control a harmless no-op; this is
 * what stops the UI from *offering* it — a torch button on a phone with no flash is a
 * button that lies about what pressing it will do (Phase 2).
 *
 * Only optional, hardware- or feature-dependent controls appear here. Things every camera
 * can always do — arm, alias, zoom, view clips — are not gated and are not listed.
 */
object ControlGating {

    data class Gates(
        val torch: Boolean,
        val pushToTalk: Boolean,
        val lensSwitch: Boolean,
        val siren: Boolean,
        val spotlight: Boolean,
        val soundDetection: Boolean,
        val hardwareProtection: Boolean,
    )

    fun gatesFor(capabilities: Capabilities): Gates = Gates(
        torch = capabilities.torch,
        pushToTalk = capabilities.audioOut,
        lensSwitch = capabilities.multiLens,
        siren = capabilities.siren,
        spotlight = capabilities.spotlight,
        soundDetection = capabilities.soundDetection,
        hardwareProtection = capabilities.hardwareProtection,
    )

    /** One short line for a disabled control, so it says why rather than vanishing. */
    fun reasonUnavailable(control: Control): String = when (control) {
        Control.TORCH -> "This camera has no flash."
        Control.PUSH_TO_TALK -> "This camera has no speaker."
        Control.LENS_SWITCH -> "This camera has only one lens."
        Control.SIREN -> "This camera has no siren."
        Control.SPOTLIGHT -> "This camera has no spotlight."
        Control.SOUND_DETECTION -> "This camera cannot detect sound."
        Control.HARDWARE_PROTECTION -> "This camera cannot watch its own heat and battery."
    }

    enum class Control { TORCH, PUSH_TO_TALK, LENS_SWITCH, SIREN, SPOTLIGHT, SOUND_DETECTION, HARDWARE_PROTECTION }
}
