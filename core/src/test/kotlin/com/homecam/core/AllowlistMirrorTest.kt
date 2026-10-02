package com.homecam.core

import com.homecam.core.crypto.IdentityKey
import com.homecam.core.pairing.AllowlistMirror
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistMirrorTest {
    private val alice = IdentityKey(ByteArray(8) { 1 })
    private val bob = IdentityKey(ByteArray(8) { 2 })

    @Test
    fun `a snapshot computed before a revoke cannot readmit the revoked key`() {
        val mirror = AllowlistMirror()
        mirror.onSnapshot(setOf(alice, bob))

        mirror.revoked(alice)
        mirror.onSnapshot(setOf(alice, bob)) // emitted before the delete, delivered after the revoke

        assertFalse(mirror.isAllowed(alice))
        assertTrue(mirror.isAllowed(bob))
    }

    @Test
    fun `once the database no longer lists the key the tombstone is gone and a re-pairing admits it`() {
        val mirror = AllowlistMirror()
        mirror.onSnapshot(setOf(alice))
        mirror.revoked(alice)
        mirror.onSnapshot(emptySet())

        mirror.added(alice)
        mirror.onSnapshot(emptySet()) // a snapshot from before the new row was written

        assertTrue("a fresh pairing must survive an older snapshot", mirror.isAllowed(alice))
        mirror.onSnapshot(setOf(alice))
        assertTrue(mirror.isAllowed(alice))
    }

    @Test
    fun `a key revoked before any snapshot saw it still cannot come back from one that did`() {
        val mirror = AllowlistMirror()
        mirror.added(alice)
        mirror.revoked(alice)
        mirror.onSnapshot(setOf(alice)) // written by the pairing, read before the revoke deleted it
        assertFalse(mirror.isAllowed(alice))
        mirror.onSnapshot(emptySet())
        assertFalse(mirror.isAllowed(alice))
    }

    @Test
    fun `revoke all closes every key including one still pending`() {
        val mirror = AllowlistMirror()
        mirror.onSnapshot(setOf(alice))
        mirror.added(bob)

        mirror.revokedAll()
        mirror.onSnapshot(setOf(alice, bob))

        assertFalse(mirror.isAllowed(alice))
        assertFalse(mirror.isAllowed(bob))
    }

    @Test
    fun `an unknown key is refused and a newly paired one is admitted at once`() {
        val mirror = AllowlistMirror()
        assertFalse(mirror.isAllowed(alice))
        mirror.added(alice)
        assertTrue(mirror.isAllowed(alice))
    }
}
