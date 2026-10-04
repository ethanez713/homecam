package com.homecam.core.harness

import com.homecam.proto.AuditEvent
import org.junit.Assert.assertTrue

/** A flat diagnostics or ntfy line. The camera writes one object per line and nothing nested. */
internal class JsonLine(val text: String) {
    fun field(name: String): String? {
        val needle = "\"$name\""
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(needle, from)
            if (at < 0) return null
            var i = at + needle.length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != ':') {
                from = at + needle.length
                continue
            }
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) return null
            return if (text[i] == '"') readString(i + 1) else {
                val end = (i until text.length).firstOrNull { text[it] == ',' || text[it] == '}' } ?: text.length
                text.substring(i, end).trim()
            }
        }
        return null
    }

    fun long(name: String): Long? = field(name)?.toLongOrNull()

    private fun readString(start: Int): String {
        val out = StringBuilder()
        var i = start
        while (i < text.length) {
            when (val c = text[i]) {
                '\\' -> {
                    if (i + 1 >= text.length) return out.toString()
                    when (val escaped = text[i + 1]) {
                        'u' -> {
                            if (i + 6 > text.length) return out.toString()
                            out.append(text.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 6
                        }
                        'n' -> { out.append('\n'); i += 2 }
                        'r' -> { out.append('\r'); i += 2 }
                        't' -> { out.append('\t'); i += 2 }
                        else -> { out.append(escaped); i += 2 }
                    }
                }
                '"' -> return out.toString()
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}

internal fun assertPass(line: String, ok: Boolean, evidence: String) {
    assertTrue("$line\n$evidence", ok)
}

internal fun eventLines(jsonl: String, event: String): Set<String> =
    jsonl.lineSequence()
        .filter { it.isNotBlank() && JsonLine(it).field("event") == event }
        .toSet()

internal fun countEvent(jsonl: String, event: String): Int =
    jsonl.lineSequence().count { it.isNotBlank() && JsonLine(it).field("event") == event }

/** Lines written after [marker]. A missing marker means the file rotated, so the whole file is new. */
internal fun linesAfter(jsonl: String, marker: String?): List<String> {
    val lines = jsonl.lineSequence().filter { it.isNotBlank() }.toList()
    if (marker.isNullOrBlank()) return lines
    val at = lines.lastIndexOf(marker)
    if (at < 0) return lines
    return lines.subList(at + 1, lines.size)
}

internal fun lastLine(jsonl: String): String? =
    jsonl.lineSequence().lastOrNull { it.isNotBlank() }

/**
 * Pages the audit RPC. [untilMs] of 0 is only the first call: the camera treats that as its
 * own clock, and a later 0 would jump back to the head instead of walking older rows.
 */
internal object AuditPages {
    suspend fun headSeq(client: HarnessClient): Long =
        client.audit(0, 0, 1).eventsList.maxOfOrNull { it.seq } ?: 0L

    suspend fun after(client: HarnessClient, seq: Long): List<AuditEvent> {
        val out = ArrayList<AuditEvent>()
        val seen = HashSet<Long>()
        var until = 0L
        var first = true
        repeat(MAX_PAGES) {
            val page = client.audit(0, if (first) 0L else until, PAGE).eventsList
            first = false
            if (page.isEmpty()) return out
            var added = 0
            var oldestMs = Long.MAX_VALUE
            var crossed = false
            for (event in page) {
                if (event.atMs < oldestMs) oldestMs = event.atMs
                if (event.seq <= seq) {
                    crossed = true
                    continue
                }
                if (seen.add(event.seq)) {
                    out.add(event)
                    added++
                }
            }
            if (page.size < PAGE || crossed) return out
            if (oldestMs <= 1L) return out
            until = if (added == 0) oldestMs - 1 else oldestMs
        }
        return out
    }

    private const val PAGE = 2000
    private const val MAX_PAGES = 20
}
