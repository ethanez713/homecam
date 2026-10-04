package com.homecam.core.diagnostics

import com.homecam.core.health.HealthAlertLabels
import java.security.SecureRandom

/** What an alert was, as a label kind. The line stores [wire], never the labels themselves. */
enum class AlertKind(val wire: String) {
    SESSION("session"),
    DETECTION("detection"),
    HEALTH("health"),
    PING("ping"),
}

/**
 * JSONL lines for the viewer alert log: `ts`, a per-process `run` id, and `event`, then
 * shape fields only. No topic, thumbnail, label or message body has a parameter here.
 */
object DiagnosticsLines {
    /** First label the camera puts on a session alert. */
    const val SESSION_LABEL = "session"

    fun newRunId(): String =
        ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * Milliseconds after the push server had the message. [serverTimeSeconds] is the
     * server's own clock, so the two phones need not agree. Null when the server gave no
     * time: a delay measured from the epoch would be a bogus reading.
     */
    fun delayMs(serverTimeSeconds: Long, nowMs: Long): Long? =
        if (serverTimeSeconds > 0) nowMs - serverTimeSeconds * 1000 else null

    /** [firstLabel] is the opened payload's first label, or null when it carried none. */
    fun alertKind(opened: Boolean, firstLabel: String?): AlertKind = when {
        !opened -> AlertKind.PING
        firstLabel != null && HealthAlertLabels.isHealth(firstLabel) -> AlertKind.HEALTH
        firstLabel == SESSION_LABEL -> AlertKind.SESSION
        else -> AlertKind.DETECTION
    }

    fun alert(ts: Long, run: String, delayMs: Long?, repeat: Boolean, kind: AlertKind, idle: Boolean): String =
        line(ts, run, "alert", buildList {
            if (delayMs != null) add("delay_ms" to delayMs)
            add("repeat" to repeat)
            add("kind" to kind.wire)
            add("idle" to idle)
        })

    fun streamDrop(ts: Long, run: String, backoffMs: Long): String =
        line(ts, run, "stream_drop", listOf("backoff_ms" to backoffMs))

    fun reconnect(ts: Long, run: String, backoffMs: Long): String =
        line(ts, run, "reconnect", listOf("backoff_ms" to backoffMs))

    private fun line(ts: Long, run: String, event: String, fields: List<Pair<String, Any>>): String =
        buildString {
            append("{\"ts\":").append(ts)
            append(",\"run\":").append(jsonString(run))
            append(",\"event\":").append(jsonString(event))
            for ((key, value) in fields) {
                append(',').append(jsonString(key)).append(':')
                when (value) {
                    is Boolean -> append(if (value) "true" else "false")
                    is Long -> append(value)
                    is String -> append(jsonString(value))
                    else -> error("diagnostics field '$key' has no JSON encoding")
                }
            }
            append('}')
        }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
