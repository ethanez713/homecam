package com.homecam.core.rpc

/**
 * The wire-protocol version each end advertises in its [com.homecam.proto.Capabilities].
 * Bumped only when a change would be misread by an older peer that cannot see it coming;
 * additive service/control ids do not need it, because the dispatcher already drops
 * unknown ids silently (§4.2).
 */
object Protocol {
    const val VERSION = 1
}
