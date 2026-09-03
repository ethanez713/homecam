package com.homecam.core.media

import com.homecam.proto.Clip

/** Filtering clips by detection label (Phase 10.3), reusing the labels already on each clip. */
object ClipFilter {
    fun withLabel(clips: List<Clip>, label: String): List<Clip> =
        clips.filter { label in it.labelsList }
}
