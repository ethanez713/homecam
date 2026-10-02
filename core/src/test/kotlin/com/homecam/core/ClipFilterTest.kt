package com.homecam.core

import com.homecam.core.media.ClipFilter
import com.homecam.proto.Clip
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipFilterTest {

    private fun clip(id: String, vararg labels: String, pinned: Boolean = false) =
        Clip.newBuilder().setId(id).addAllLabels(labels.toList()).setPinned(pinned).build()

    @Test
    fun `the list leaves out footage with no detection unless it is pinned`() {
        val listed = ClipFilter.listed(
            listOf(clip("quiet"), clip("person", "person"), clip("kept", pinned = true), clip("both", "pet", pinned = true))
        )

        assertEquals(listOf("person", "kept", "both"), listed.map { it.id })
    }
}
