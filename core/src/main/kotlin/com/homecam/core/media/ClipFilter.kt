package com.homecam.core.media

import com.homecam.proto.Clip

/** Filtering clips by detection label (Phase 10.3), reusing the labels already on each clip. */
object ClipFilter {
    /**
     * What the clip list shows. Footage in which nothing was detected is reached by scrubbing
     * the timeline; a pin is the user saying a clip matters, so it is listed whatever is in it.
     */
    fun listed(clips: List<Clip>): List<Clip> =
        clips.filter { it.labelsCount > 0 || it.pinned }

    fun withLabel(clips: List<Clip>, label: String): List<Clip> =
        clips.filter { label in it.labelsList }
}
