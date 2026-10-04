package com.homecam.core

import com.homecam.core.diagnostics.AlertKind
import com.homecam.core.diagnostics.AudioListen
import com.homecam.core.diagnostics.DiagnosticsLines
import com.homecam.core.diagnostics.PeerPath
import com.homecam.core.diagnostics.PowerSavingReason
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

    @Test
    fun `a throttle line names the step, whether it engaged, and the reading`() {
        assertEquals(
            """{"ts":1,"run":"ab12cd34","event":"throttle","step":"fps","state":"engaged","battery_temp_c":36.5,"thermal_status":"moderate"}""",
            DiagnosticsLines.throttle(1, "ab12cd34", "fps", engaged = true, batteryTempC = 36.5, thermalStatus = "moderate"),
        )
        assertEquals(
            """{"ts":2,"run":"ab12cd34","event":"throttle","step":"screen","state":"released","battery_temp_c":34,"thermal_status":"unknown"}""",
            DiagnosticsLines.throttle(2, "ab12cd34", "screen", engaged = false, batteryTempC = 34.0, thermalStatus = "unknown"),
        )
    }

    @Test
    fun `a power saving line carries state and reason`() {
        assertEquals(
            """{"ts":3,"run":"ab12cd34","event":"power_saving","state":"engaged","reason":"idle"}""",
            DiagnosticsLines.powerSaving(3, "ab12cd34", engaged = true, reason = PowerSavingReason.IDLE),
        )
        assertEquals(
            """{"ts":4,"run":"ab12cd34","event":"power_saving","state":"released","reason":"viewer"}""",
            DiagnosticsLines.powerSaving(4, "ab12cd34", engaged = false, reason = PowerSavingReason.VIEWER),
        )
    }

    @Test
    fun `an audio listen line names the feed, the transition and the reason`() {
        assertEquals(
            """{"ts":5,"run":"ab12cd34","event":"audio_listen","feed":"tap","state":"stop","reason":"mic_off"}""",
            DiagnosticsLines.audioListen(5, "ab12cd34", AudioListen.TAP, started = false, reason = AudioListen.MIC_OFF),
        )
        assertEquals(
            """{"ts":6,"run":"ab12cd34","event":"audio_listen","feed":"live","state":"start","reason":"viewer_live"}""",
            DiagnosticsLines.audioListen(6, "ab12cd34", AudioListen.LIVE, started = true, reason = AudioListen.VIEWER_LIVE),
        )
    }

    @Test
    fun `a tls line records the protocol and the peer class, and a reason only when refused`() {
        assertEquals(
            """{"ts":7,"run":"ab12cd34","event":"tls","protocol":"TLSv1.3","peer":"tailnet","outcome":"accepted"}""",
            DiagnosticsLines.tls(7, "ab12cd34", "TLSv1.3", PeerPath.TAILNET, accepted = true, reason = null),
        )
        assertEquals(
            """{"ts":8,"run":"ab12cd34","event":"tls","protocol":"TLSv1.2","peer":"lan","outcome":"refused","reason":"auth:access_denied"}""",
            DiagnosticsLines.tls(8, "ab12cd34", "TLSv1.2", PeerPath.LAN, accepted = false, reason = "auth:access_denied"),
        )
        assertEquals(
            """{"ts":8,"run":"ab12cd34","event":"tls","protocol":"TLSv1.2","peer":"lan","outcome":"refused","reason":"limit","suppressed":4}""",
            DiagnosticsLines.tls(
                8, "ab12cd34", "TLSv1.2", PeerPath.LAN, accepted = false, reason = "limit", suppressed = 4,
            ),
        )
        assertEquals(
            """{"ts":8,"run":"ab12cd34","event":"tls","protocol":"TLSv1.2","peer":"lan","outcome":"refused","reason":"limit"}""",
            DiagnosticsLines.tls(
                8, "ab12cd34", "TLSv1.2", PeerPath.LAN, accepted = false, reason = "limit", suppressed = 0,
            ),
        )
        assertEquals(
            """{"ts":9,"run":"ab12cd34","event":"tls","protocol":"TLSv1.3","peer":"other","outcome":"accepted"}""",
            DiagnosticsLines.tls(9, "ab12cd34", "TLSv1.3", PeerPath.OTHER, accepted = true, reason = ""),
        )
    }

    @Test
    fun `a recording timer line carries the mode, the revert and how the peer connected`() {
        assertEquals(
            """{"ts":10,"run":"ab12cd34","event":"recording_timer_set","mode":"CONTINUOUS","revert_to":"DETECTION","due_at_ms":90000,"via":"tailnet"}""",
            DiagnosticsLines.recordingTimerSet(
                10, "ab12cd34", mode = "CONTINUOUS", revertTo = "DETECTION", dueAtMs = 90_000, via = PeerPath.TAILNET,
            ),
        )
    }

    @Test
    fun `a spotlight line mirrors siren and records duration only on the off`() {
        assertEquals(
            """{"ts":11,"run":"ab12cd34","event":"spotlight","on":true,"auto":true,"luma":12.5}""",
            DiagnosticsLines.spotlight(11, "ab12cd34", on = true, auto = true, luma = 12.5, durationMs = 20_000),
        )
        assertEquals(
            """{"ts":12,"run":"ab12cd34","event":"spotlight","on":false,"auto":true,"luma":12.5,"duration_ms":20000}""",
            DiagnosticsLines.spotlight(12, "ab12cd34", on = false, auto = true, luma = 12.5, durationMs = 20_000),
        )
        assertEquals(
            """{"ts":13,"run":"ab12cd34","event":"spotlight","on":false,"auto":false,"luma":0}""",
            DiagnosticsLines.spotlight(13, "ab12cd34", on = false, auto = false, luma = 0.0, durationMs = null),
        )
    }

    @Test
    fun `a shaped line stays one line when a field needs escaping`() {
        assertEquals(
            """{"ts":14,"run":"ab12cd34","event":"throttle","step":"a\"b","state":"engaged","battery_temp_c":1,"thermal_status":"none"}""",
            DiagnosticsLines.throttle(14, "ab12cd34", "a\"b", engaged = true, batteryTempC = 1.0, thermalStatus = "none"),
        )
    }
}
