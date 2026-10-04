package com.homecam.core

import com.homecam.core.diagnostics.AlertKind
import com.homecam.core.diagnostics.DiagnosticsLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLineTest {

    @Test
    fun `an alert line carries delay, repeat, kind and idle and nothing else`() {
        assertEquals(
            """{"ts":1700000000000,"run":"ab12cd34","event":"alert","delay_ms":2500,"repeat":false,"kind":"detection","idle":true}""",
            DiagnosticsLines.alert(
                ts = 1_700_000_000_000,
                run = "ab12cd34",
                delayMs = 2_500,
                repeat = false,
                kind = AlertKind.DETECTION,
                idle = true,
            ),
        )
    }

    @Test
    fun `a missing server time omits delay rather than inventing one`() {
        assertEquals(
            """{"ts":10,"run":"ab12cd34","event":"alert","repeat":true,"kind":"ping","idle":false}""",
            DiagnosticsLines.alert(10, "ab12cd34", delayMs = null, repeat = true, kind = AlertKind.PING, idle = false),
        )
    }

    @Test
    fun `delay is measured from the server clock in seconds`() {
        assertEquals(2_500L, DiagnosticsLines.delayMs(serverTimeSeconds = 10, nowMs = 12_500))
        assertEquals(0L, DiagnosticsLines.delayMs(serverTimeSeconds = 10, nowMs = 10_000))
        assertEquals(-500L, DiagnosticsLines.delayMs(serverTimeSeconds = 10, nowMs = 9_500))
        assertNull(DiagnosticsLines.delayMs(serverTimeSeconds = 0, nowMs = 12_500))
        assertNull(DiagnosticsLines.delayMs(serverTimeSeconds = -1, nowMs = 12_500))
    }

    @Test
    fun `kind is session, detection, health or ping`() {
        assertEquals(AlertKind.PING, DiagnosticsLines.alertKind(opened = false, firstLabel = "person"))
        assertEquals(AlertKind.PING, DiagnosticsLines.alertKind(opened = false, firstLabel = null))
        assertEquals(AlertKind.HEALTH, DiagnosticsLines.alertKind(opened = true, firstLabel = "health:paused:thermal"))
        assertEquals(AlertKind.HEALTH, DiagnosticsLines.alertKind(opened = true, firstLabel = "health:resumed"))
        assertEquals(AlertKind.HEALTH, DiagnosticsLines.alertKind(opened = true, firstLabel = "health:storage-critical"))
        assertEquals(AlertKind.SESSION, DiagnosticsLines.alertKind(opened = true, firstLabel = "session"))
        assertEquals(AlertKind.DETECTION, DiagnosticsLines.alertKind(opened = true, firstLabel = "person"))
        assertEquals(AlertKind.DETECTION, DiagnosticsLines.alertKind(opened = true, firstLabel = null))
        assertEquals(AlertKind.DETECTION, DiagnosticsLines.alertKind(opened = true, firstLabel = ""))
        assertEquals(AlertKind.DETECTION, DiagnosticsLines.alertKind(opened = true, firstLabel = "health"))
        for (kind in AlertKind.entries) {
            val line = DiagnosticsLines.alert(1, "ab12cd34", 1, false, kind, false)
            assertTrue(line.contains("\"kind\":\"${kind.wire}\""))
        }
    }

    @Test
    fun `stream drop and reconnect carry only the backoff`() {
        assertEquals(
            """{"ts":5,"run":"ab12cd34","event":"stream_drop","backoff_ms":4000}""",
            DiagnosticsLines.streamDrop(ts = 5, run = "ab12cd34", backoffMs = 4_000),
        )
        assertEquals(
            """{"ts":9,"run":"ab12cd34","event":"reconnect","backoff_ms":4000}""",
            DiagnosticsLines.reconnect(ts = 9, run = "ab12cd34", backoffMs = 4_000),
        )
    }

    @Test
    fun `a line stays one line when a field needs escaping`() {
        assertEquals(
            """{"ts":1,"run":"a\"b\\c\nd","event":"alert","delay_ms":1,"repeat":false,"kind":"session","idle":false}""",
            DiagnosticsLines.alert(1, "a\"b\\c\nd", delayMs = 1, repeat = false, kind = AlertKind.SESSION, idle = false),
        )
    }

    @Test
    fun `a run id is eight hex characters`() {
        val id = DiagnosticsLines.newRunId()
        assertEquals(8, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
