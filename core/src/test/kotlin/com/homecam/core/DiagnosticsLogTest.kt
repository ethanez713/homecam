package com.homecam.core

import com.homecam.core.diagnostics.DiagnosticsLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiagnosticsLogTest {

    private fun <T> withLog(name: String = "camera.jsonl", maxBytes: Long, block: (File, DiagnosticsLog) -> T): T {
        val dir = Files.createTempDirectory("homecam-diag").toFile()
        try {
            val file = File(dir, name)
            return block(file, DiagnosticsLog(file, maxBytes = maxBytes))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `each append is one line and a failed write is reported rather than thrown`() {
        withLog(maxBytes = 100) { file, log ->
            log.append("""{"event":"a"}""")
            log.append("""{"event":"b"}""")
            assertEquals("""{"event":"a"}""" + "\n" + """{"event":"b"}""" + "\n", file.readText())
        }
        val dir = Files.createTempDirectory("homecam-diag").toFile()
        try {
            val parent = File(dir, "not-a-directory").apply { writeText("x") }
            val failures = mutableListOf<Throwable>()
            DiagnosticsLog(File(parent, "viewer.jsonl")) { failures += it }.append("line")
            assertEquals(1, failures.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rotation keeps a single generation and waits until the cap is exceeded`() {
        withLog(name = "viewer.jsonl", maxBytes = 10) { file, log ->
            log.append("12345")
            log.append("67890")
            val rotated = File(file.parentFile, "viewer.1.jsonl")
            assertEquals("12345\n67890\n", file.readText())
            assertFalse(rotated.exists())

            log.append("next")
            assertEquals("next\n", file.readText())
            assertEquals("12345\n67890\n", rotated.readText())

            log.append("yyyyyyyyyyy")
            log.append("Z")
            assertEquals("Z\n", file.readText())
            val kept = rotated.readText()
            assertTrue(kept.startsWith("next\n"))
            assertFalse(kept.contains("12345"))
        }
    }

    @Test
    fun `a file sitting exactly at the cap is not rotated`() {
        withLog(maxBytes = 4) { file, log ->
            file.writeText("abcd")
            log.append("Z")
            assertEquals("abcdZ\n", file.readText())
            assertFalse(File(file.parentFile, "camera.1.jsonl").exists())
        }
    }
}
