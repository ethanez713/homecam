package com.homecam.core.sound

/**
 * Maps YAMNet's 521 AudioSet classes onto the small user-facing set the viewer chooses
 * from (Phase 6.3), exactly as the vision model maps COCO onto person/vehicle/pet/package.
 * Some of these are approximations — see models/README.md — and should be read as such.
 * Anything not in the map is not a sound this camera reports.
 */
object SoundClasses {

    const val DOG = "dog"
    const val ALARM = "alarm"
    const val DOORBELL = "doorbell"
    const val GLASS = "glass"
    const val SHOUT = "shout"
    const val KNOCK = "knock"

    /** The categories a viewer can turn on, in a stable order. */
    val ALL = listOf(DOG, ALARM, DOORBELL, GLASS, SHOUT, KNOCK)

    private val BY_AUDIOSET: Map<String, String> = buildMap {
        listOf("Dog", "Bark", "Yip", "Howl", "Bow-wow", "Growling", "Whimper (dog)").forEach { put(it, DOG) }
        listOf("Alarm", "Alarm clock", "Siren", "Civil defense siren", "Buzzer",
            "Smoke detector, smoke alarm", "Fire alarm").forEach { put(it, ALARM) }
        listOf("Doorbell", "Ding-dong").forEach { put(it, DOORBELL) }
        listOf("Glass", "Chink, clink", "Shatter", "Breaking").forEach { put(it, GLASS) }
        listOf("Shout", "Bellow", "Yell", "Children shouting", "Screaming").forEach { put(it, SHOUT) }
        listOf("Knock", "Tap", "Slam", "Thump, thud").forEach { put(it, KNOCK) }
    }

    /** The user-facing category for a raw AudioSet label, or null if it is not one we report. */
    fun categoryFor(audiosetLabel: String): String? = BY_AUDIOSET[audiosetLabel]
}
