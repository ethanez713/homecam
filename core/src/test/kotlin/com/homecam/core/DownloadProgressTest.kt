package com.homecam.core

import com.homecam.core.media.DownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun `progress is the share of the listed size that has arrived`() {
        assertEquals(0.25f, DownloadProgress(25, 100).fraction)
        assertEquals(25, DownloadProgress(25, 100).percent)
        assertEquals(0, DownloadProgress(0, 100).percent)
    }

    @Test
    fun `a clip that grew past its listed size reads as complete, not over`() {
        assertEquals(1f, DownloadProgress(120, 100).fraction)
        assertEquals(100, DownloadProgress(120, 100).percent)
    }

    @Test
    fun `an unknown size has no fraction`() {
        assertNull(DownloadProgress(50, 0).fraction)
        assertNull(DownloadProgress(50, 0).percent)
    }
}
