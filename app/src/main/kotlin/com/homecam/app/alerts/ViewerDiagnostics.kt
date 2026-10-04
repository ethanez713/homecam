package com.homecam.app.alerts

import android.content.Context
import android.util.Log
import com.homecam.core.diagnostics.AlertKind
import com.homecam.core.diagnostics.DiagnosticsLines
import com.homecam.core.diagnostics.DiagnosticsLog
import java.io.File

/**
 * The viewer's alert-delivery record (`files/diagnostics/viewer.jsonl`). logcat wraps within
 * a day, so a delay on an idle phone has to be written here to be diagnosable afterwards.
 * One run id per process. Shape only — the topic, thumbnail and labels never reach it.
 */
class ViewerDiagnostics(
    context: Context,
    directory: File = File(context.filesDir, "diagnostics"),
) {
    private val log = DiagnosticsLog(File(directory, "viewer.jsonl")) {
        Log.w(TAG, "diagnostics write failed", it)
    }
    val run: String = DiagnosticsLines.newRunId()

    fun alert(delayMs: Long?, repeat: Boolean, kind: AlertKind, idle: Boolean) {
        log.append(DiagnosticsLines.alert(System.currentTimeMillis(), run, delayMs, repeat, kind, idle))
    }

    fun streamDrop(backoffMs: Long) {
        log.append(DiagnosticsLines.streamDrop(System.currentTimeMillis(), run, backoffMs))
    }

    fun reconnect(backoffMs: Long) {
        log.append(DiagnosticsLines.reconnect(System.currentTimeMillis(), run, backoffMs))
    }

    private companion object {
        const val TAG = "ViewerDiagnostics"
    }
}
