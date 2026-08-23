package com.homecam.core

import com.homecam.core.audit.AuditChain
import com.homecam.proto.AuditEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditChainTest {

    private fun chainOf(vararg kinds: AuditEvent.Kind): MutableList<AuditEvent> {
        val out = mutableListOf<AuditEvent>()
        var previous = AuditChain.GENESIS
        kinds.forEachIndexed { index, kind ->
            val linked = AuditChain.link(
                previous,
                AuditEvent.newBuilder()
                    .setSeq(index.toLong())
                    .setAtMs(1_700_000_000_000L + index * 1_000L)
                    .setKind(kind)
                    .setViewerFingerprint("7K2P-QM4X-9DFA")
                    .setViewerLabel("Ethan's S25")
                    .setSourceIp("192.168.1.42")
                    .build(),
            )
            out += linked
            previous = linked.chainHash.toByteArray()
        }
        return out
    }

    @Test
    fun `an untouched chain verifies`() {
        val chain = chainOf(
            AuditEvent.Kind.CAMERA_STARTED,
            AuditEvent.Kind.PAIRING_COMPLETED,
            AuditEvent.Kind.SESSION_OPENED,
            AuditEvent.Kind.SESSION_CLOSED,
        )
        val result = AuditChain.verify(chain)
        assertTrue(result.valid)
        assertEquals(-1, result.firstBrokenSeq)
    }

    @Test
    fun `editing a row breaks the chain at that row`() {
        val chain = chainOf(
            AuditEvent.Kind.CAMERA_STARTED,
            AuditEvent.Kind.SESSION_OPENED,
            AuditEvent.Kind.SESSION_CLOSED,
        )
        chain[1] = chain[1].toBuilder().setViewerLabel("Someone else").build()

        val result = AuditChain.verify(chain)
        assertFalse(result.valid)
        assertEquals(1, result.firstBrokenSeq)
    }

    @Test
    fun `deleting a row breaks the chain`() {
        val chain = chainOf(
            AuditEvent.Kind.CAMERA_STARTED,
            AuditEvent.Kind.CONNECTION_REJECTED,
            AuditEvent.Kind.SESSION_OPENED,
        )
        chain.removeAt(1)

        val result = AuditChain.verify(chain)
        assertFalse(result.valid)
        assertEquals(2, result.firstBrokenSeq)
    }

    @Test
    fun `re-linking a tampered row still fails because prev_hash no longer matches`() {
        val chain = chainOf(
            AuditEvent.Kind.CAMERA_STARTED,
            AuditEvent.Kind.SESSION_OPENED,
            AuditEvent.Kind.SESSION_CLOSED,
        )
        // The realistic attack: edit a row and recompute *its* hash, hoping nobody checks
        // the rows after it.
        val forged = chain[1].toBuilder().setSourceIp("10.0.0.1").build()
        chain[1] = AuditChain.link(chain[0].chainHash.toByteArray(), forged)

        val result = AuditChain.verify(chain)
        assertFalse(result.valid)
        assertEquals(2, result.firstBrokenSeq)
    }

    @Test
    fun `the same event at a different position hashes differently`() {
        val chain = chainOf(AuditEvent.Kind.SESSION_OPENED, AuditEvent.Kind.SESSION_OPENED)
        assertFalse(chain[0].chainHash == chain[1].chainHash)
    }
}
