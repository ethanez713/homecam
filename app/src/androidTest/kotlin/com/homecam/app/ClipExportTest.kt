package com.homecam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.viewer.ClipExport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 10.4: exporting a clip to the gallery copies it byte-for-byte, so whatever was
 * muxed into it — the detection metadata track — travels with it. A verbatim round-trip is
 * what guarantees the exported file stays self-describing.
 */
@RunWith(AndroidJUnit4::class)
class ClipExportTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exportWritesTheClipVerbatimToTheGallery() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val source = File(context.cacheDir, "export-src-${System.nanoTime()}.mp4").apply { writeBytes(payload) }

        val uri = ClipExport.toGallery(context, source, "homecam-test-${System.nanoTime()}.mp4")
        assertNotNull("export returned no uri", uri)
        try {
            val written = context.contentResolver.openInputStream(uri!!)!!.use { it.readBytes() }
            assertArrayEquals("exported bytes differ from the source", payload, written)
        } finally {
            context.contentResolver.delete(uri!!, null, null)
            source.delete()
        }
    }
}
