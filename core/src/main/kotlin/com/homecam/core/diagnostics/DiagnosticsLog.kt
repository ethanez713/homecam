package com.homecam.core.diagnostics

import java.io.File

/**
 * Append-only JSONL capped at [MAX_BYTES], with one rotated generation beside the live file
 * (`camera.jsonl` → `camera.1.jsonl`). Shared so the camera and viewer logs rotate the same
 * way. Nothing reads the file at runtime; it is pulled after a run.
 *
 * Rotation happens only once the file is already longer than the cap, and a failed write is
 * reported rather than thrown: a diagnostics line must not take down the caller.
 */
class DiagnosticsLog(
    private val file: File,
    private val maxBytes: Long = MAX_BYTES,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val rotated = File(file.parentFile, file.nameWithoutExtension + ".1.jsonl")
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
    }

    fun append(line: String) {
        synchronized(lock) {
            runCatching {
                if (file.length() > maxBytes) {
                    rotated.delete()
                    file.renameTo(rotated)
                }
                file.appendText(line + "\n")
            }.onFailure(onFailure)
        }
    }

    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
