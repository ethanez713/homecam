package com.homecam.core.pairing

import com.homecam.core.crypto.IdentityKey

/**
 * The in-memory copy of the allowlist the handshake reads without suspending. Two writers
 * feed it: the database's change flow, and the pairing and revoke calls that act before the
 * flow has caught up. A flow emission computed before a revoke can arrive after it, so a
 * snapshot must never undo a decision that is newer than itself.
 *
 * A revoke leaves a tombstone that outlives any snapshot still listing the key, and is
 * dropped only by one that no longer does. A pairing is held as pending the same way until
 * a snapshot contains it. A revoked key can therefore never be readmitted by a stale
 * snapshot, which is the property the revoke control exists to provide.
 */
class AllowlistMirror {
    private var confirmed: Set<IdentityKey> = emptySet()
    private val pending = mutableSetOf<IdentityKey>()
    private val tombstones = mutableSetOf<IdentityKey>()

    @Volatile private var effective: Set<IdentityKey> = emptySet()

    fun isAllowed(key: IdentityKey): Boolean = key in effective

    @Synchronized
    fun onSnapshot(keys: Set<IdentityKey>) {
        confirmed = keys
        pending.removeAll(keys)
        tombstones.retainAll(keys)
        publish()
    }

    @Synchronized
    fun added(key: IdentityKey) {
        tombstones.remove(key)
        if (key !in confirmed) pending += key
        publish()
    }

    @Synchronized
    fun revoked(key: IdentityKey) {
        pending.remove(key)
        tombstones += key
        publish()
    }

    @Synchronized
    fun revokedAll() {
        tombstones += confirmed + pending
        pending.clear()
        publish()
    }

    private fun publish() {
        effective = (confirmed + pending) - tombstones
    }
}
