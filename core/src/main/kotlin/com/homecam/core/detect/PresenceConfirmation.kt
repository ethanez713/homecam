package com.homecam.core.detect

/**
 * Holds a category until its sightings span [MIN_PRESENCE_MS]. A real person cannot
 * appear and vanish inside a second, so a shorter episode is discounted instead of
 * counted. Sightings stay one episode while each arrives within [LINK_GAP_MS] of the
 * previous; that gap sits inside the recorder's pre-roll, so the first sighting is
 * still in the clip when the episode confirms. Once confirmed, later sightings in the
 * episode pass at once.
 */
class PresenceConfirmation(
    private val heldCategories: Set<String>,
) {
    data class Discounted(val label: String, val sightings: Int, val spanMs: Long)

    sealed class Action {
        data object Hold : Action()

        /** [priorSightings] is how many earlier sightings the caller kept, oldest first. */
        data class Confirm(val priorSightings: Int) : Action()

        data object Pass : Action()
    }

    data class Outcome(val actions: Map<String, Action>, val discounted: List<Discounted>)

    private class Episode(
        val firstAtMs: Long,
        var lastAtMs: Long,
        var sightings: Int,
        var confirmed: Boolean,
    )

    private val episodes = LinkedHashMap<String, Episode>()

    /**
     * One detector frame. [present] is every category accepted on it. A category outside
     * [heldCategories] is left out of [Outcome.actions], so the caller forwards it unchanged.
     */
    fun observe(atMs: Long, present: Set<String>): Outcome {
        val discounted = mutableListOf<Discounted>()
        val expired = episodes.keys.filter { label ->
            atMs - episodes.getValue(label).lastAtMs >= LINK_GAP_MS
        }
        for (label in expired) {
            val episode = episodes.remove(label) ?: continue
            if (!episode.confirmed) {
                discounted += Discounted(
                    label = label,
                    sightings = episode.sightings,
                    spanMs = episode.lastAtMs - episode.firstAtMs,
                )
            }
        }

        val actions = LinkedHashMap<String, Action>()
        for (label in present) {
            if (label !in heldCategories) continue
            val episode = episodes[label]
            if (episode == null) {
                episodes[label] = Episode(atMs, atMs, sightings = 1, confirmed = false)
                actions[label] = Action.Hold
                continue
            }
            episode.lastAtMs = atMs
            episode.sightings += 1
            val span = atMs - episode.firstAtMs
            actions[label] = when {
                episode.confirmed -> Action.Pass
                span >= MIN_PRESENCE_MS -> {
                    episode.confirmed = true
                    Action.Confirm(priorSightings = episode.sightings - 1)
                }
                else -> Action.Hold
            }
        }
        return Outcome(actions, discounted)
    }

    companion object {
        const val LINK_GAP_MS = 4_000L
        const val MIN_PRESENCE_MS = 1_000L
    }
}
