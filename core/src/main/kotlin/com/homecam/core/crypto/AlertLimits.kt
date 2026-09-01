package com.homecam.core.crypto

/**
 * Size ceilings for a sealed detection alert (Phase 1.5).
 *
 * A detection alert carries a thumbnail, and the whole thing is sealed and then Base64'd
 * into a push-provider message body. ntfy — the reference provider — accepts a body of a
 * few kilobytes on its simple POST path, so the thumbnail has to be bounded on the camera
 * side rather than discovered to be too big at delivery time. [MAX_THUMBNAIL_BYTES] is the
 * budget the camera compresses a thumbnail down to; [MAX_SEALED_BODY_BYTES] is the ceiling
 * the resulting Base64 body must stay under, with headroom for the ECIES envelope and the
 * payload's other fields. When a thumbnail cannot fit, the alert is sent without it rather
 * than dropped — a content-free ping still tells you to look.
 */
object AlertLimits {
    const val MAX_THUMBNAIL_BYTES = 2560
    const val MAX_SEALED_BODY_BYTES = 4096
}
