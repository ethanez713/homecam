package com.homecam.core.audit

import com.google.protobuf.ByteString
import com.homecam.core.crypto.Canonical
import com.homecam.proto.AuditEvent
import java.security.MessageDigest

/**
 * The hash chain behind the access log (§2.7). Each entry commits to the previous
 * entry's hash, so removing or editing a row breaks verification from that row onward —
 * which is what makes the log evidence rather than a list.
 *
 * The chain covers the entry's semantic fields, deliberately *not* the serialized proto:
 * protobuf serialization is not canonical, so hashing the bytes would make a chain that
 * fails after a library upgrade.
 */
object AuditChain {

    val GENESIS: ByteArray = ByteArray(32)

    fun hash(previous: ByteArray, event: AuditEvent): ByteArray {
        val body = Canonical.encode(
            previous,
            Canonical.long(event.seq),
            Canonical.long(event.atMs),
            Canonical.int(event.kindValue),
            event.viewerFingerprint.toByteArray(),
            event.viewerLabel.toByteArray(),
            event.sourceIp.toByteArray(),
            event.detail.toByteArray(),
        )
        return MessageDigest.getInstance("SHA-256").digest("homecam-audit-v1".toByteArray() + body)
    }

    /** Fills in [AuditEvent.getPrevHash] and [AuditEvent.getChainHash] for a new entry. */
    fun link(previous: ByteArray, event: AuditEvent): AuditEvent {
        val chain = hash(previous, event)
        return event.toBuilder()
            .setPrevHash(ByteString.copyFrom(previous))
            .setChainHash(ByteString.copyFrom(chain))
            .build()
    }

    data class Verification(val valid: Boolean, val firstBrokenSeq: Long)

    /** [events] must be in ascending `seq` order and start at the genesis entry. */
    fun verify(events: List<AuditEvent>): Verification {
        var previous = GENESIS
        var expectedSeq = 0L
        for (event in events) {
            if (event.seq != expectedSeq) return Verification(false, event.seq)
            if (!event.prevHash.toByteArray().contentEquals(previous)) return Verification(false, event.seq)
            val computed = hash(previous, event)
            if (!event.chainHash.toByteArray().contentEquals(computed)) return Verification(false, event.seq)
            previous = computed
            expectedSeq++
        }
        return Verification(true, -1)
    }
}
