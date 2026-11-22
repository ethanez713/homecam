package com.homecam.core.unlock

/**
 * Labels the gesture pipeline puts on an alert. The notification shows [describe],
 * never the token.
 */
object UnlockAlerts {
    const val DOOR_UNLOCKED = "door_unlocked"
    const val WRONG_CODE = "unlock_wrong_code"
    const val LOCKED_OUT = "unlock_locked_out"
    const val ERROR = "unlock_error"

    /** The notification body for a gesture-unlock label, or null when it is not one of ours. */
    fun describe(label: String): String? = when (label) {
        DOOR_UNLOCKED -> "The door was unlocked with the gesture code."
        WRONG_CODE -> "Someone tried the wrong gesture code."
        LOCKED_OUT -> "Gesture unlock is locked out after too many wrong codes."
        ERROR -> "Gesture unlock failed."
        else -> null
    }
}
