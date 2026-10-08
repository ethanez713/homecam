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
    const val VOICE = "voice"

    /** What a loud sound is called when it matches none of the classes above. */
    const val GENERIC = "sound"

    /** The categories a viewer can turn on, in a stable order. */
    val ALL = listOf(DOG, ALARM, DOORBELL, GLASS, SHOUT, KNOCK, VOICE)

    /** Pushed until the viewer chooses otherwise. Dog and voice are common enough to be noise. */
    val DEFAULT_PUSH: Set<String> = setOf(ALARM, DOORBELL, GLASS, SHOUT, KNOCK)

    /** [configured] is null when the viewer has never chosen; an empty list is a choice of none. */
    fun pushSelection(configured: List<String>?): Set<String> = configured?.toSet() ?: DEFAULT_PUSH

    /**
     * The category to push for a sound event, or null to stay quiet. A sound that matches no
     * selected class is not pushed. Without a classifier nothing can be told apart, so every
     * sound is pushed as [GENERIC] rather than going unreported.
     */
    fun pushCategory(classifierAvailable: Boolean, recognised: String?): String? =
        if (classifierAvailable) recognised else GENERIC

    private val BY_AUDIOSET: Map<String, String> = buildMap {
        listOf("Dog", "Bark", "Yip", "Howl", "Bow-wow", "Growling", "Whimper (dog)").forEach { put(it, DOG) }
        listOf("Alarm", "Alarm clock", "Siren", "Civil defense siren", "Buzzer",
            "Smoke detector, smoke alarm", "Fire alarm").forEach { put(it, ALARM) }
        listOf("Doorbell", "Ding-dong").forEach { put(it, DOORBELL) }
        listOf("Glass", "Chink, clink", "Shatter", "Breaking").forEach { put(it, GLASS) }
        listOf("Shout", "Bellow", "Yell", "Children shouting", "Screaming").forEach { put(it, SHOUT) }
        listOf("Knock", "Tap", "Slam", "Thump, thud").forEach { put(it, KNOCK) }
        listOf("Speech", "Child speech, kid speaking", "Conversation", "Narration, monologue")
            .forEach { put(it, VOICE) }
    }

    /** The user-facing category for a raw AudioSet label, or null if it is not one we report. */
    fun categoryFor(audiosetLabel: String): String? = BY_AUDIOSET[audiosetLabel]
}
