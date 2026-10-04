package com.homecam.core.harness

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SDK adb against the camera named by `device.serial`. stdout and stderr are drained
 * together: a full stderr pipe otherwise stalls `exec-out` before the bytes we asked for arrive.
 *
 * The viewers table is read here, not over the audit RPC. The RPC has no alert endpoint, and
 * after B revokes itself there is no paired throwaway left to ask for the roster.
 */
internal class CameraAdb(private val serial: String) {
    private val adb: String = sdkAdb()
    private val temps = mutableListOf<File>()

    fun cameraJsonl(): String = catText(DIAGNOSTICS)

    fun armPairing(): String = shell(
        "am", "broadcast",
        "-n", "com.homecam.app/.debug.PairingArmReceiver",
        "-a", "com.homecam.app.debug.ARM_PAIRING",
    )

    fun readInvite(): String {
        val first = catText(INVITE)
        if (first.isNotBlank()) return first
        Thread.sleep(200)
        return catText(INVITE)
    }

    fun deleteInvite() {
        shell("run-as", PACKAGE, "rm", "-f", INVITE)
    }

    fun invitePresent(): Boolean = catBytes(INVITE) != null

    fun serviceStartCount(): Int = countEvent(cameraJsonl(), "service_start")

    fun rosterCount(): Int = withDatabase { sqlite(it, "count", null).trim().toInt() }

    fun alertEndpoint(fingerprint: String): String = withDatabase { sqlite(it, "endpoint", fingerprint).trim() }

    /** One `top` sample. The percent is the token three before the process name in toybox's layout. */
    fun cpuSample(): CpuSample {
        val text = shell("top", "-b", "-n", "1")
        val line = text.lineSequence().firstOrNull { it.contains(PACKAGE) }
        if (line == null) return CpuSample(null, text.lineSequence().take(40).joinToString("\n"))
        val tokens = line.trim().split(Regex("\\s+"))
        val at = tokens.indexOfFirst { it == PACKAGE || it.startsWith("$PACKAGE:") }
        val percent = if (at >= 3) tokens[at - 3].removeSuffix("%").toDoubleOrNull() else null
        return CpuSample(percent, line.trim())
    }

    fun deleteTemps(): Boolean {
        val copy = temps.toList()
        var gone = true
        copy.forEach { dir ->
            if (dir.exists() && !dir.deleteRecursively()) gone = false else temps.remove(dir)
        }
        return gone && temps.isEmpty()
    }

    private fun <T> withDatabase(block: (File) -> T): T {
        var last: Exception? = null
        repeat(2) {
            val dir = File(System.getProperty("java.io.tmpdir"), "gate-sec-db-${TEMPS.incrementAndGet()}")
            dir.mkdirs()
            temps.add(dir)
            try {
                val db = File(dir, "homecam.db")
                val main = catBytes("databases/homecam.db") ?: error("homecam.db missing")
                db.writeBytes(main)
                catBytes("databases/homecam.db-wal")?.let { File(dir, "homecam.db-wal").writeBytes(it) }
                catBytes("databases/homecam.db-shm")?.let { File(dir, "homecam.db-shm").writeBytes(it) }
                return block(db)
            } catch (e: Exception) {
                last = e
            } finally {
                if (!dir.exists() || dir.deleteRecursively()) temps.remove(dir)
            }
        }
        throw last ?: IllegalStateException("database pull failed")
    }

    private fun sqlite(db: File, op: String, arg: String?): String {
        val script = File(db.parentFile, "q.py")
        script.writeText(SQLITE)
        val command = mutableListOf("/usr/bin/python3", script.absolutePath, op, db.absolutePath)
        if (arg != null) command.add(arg)
        val result = process(command, 20_000)
        if (result.exit != 0) error("sqlite $op failed (${result.exit}): ${result.stderr}")
        return result.stdout.toString(StandardCharsets.UTF_8)
    }

    private fun catText(path: String): String =
        catBytes(path)?.toString(StandardCharsets.UTF_8) ?: ""

    private fun catBytes(path: String): ByteArray? {
        val result = process(listOf(adb, "-s", serial, "exec-out", "run-as", PACKAGE, "cat", path), 30_000)
        if (result.exit != 0) return null
        return result.stdout
    }

    private fun shell(vararg args: String): String {
        val result = process(listOf(adb, "-s", serial, "shell") + args, 30_000)
        val text = result.stdout.toString(StandardCharsets.UTF_8)
        if (result.exit != 0) error("adb shell ${args.joinToString(" ")} (${result.exit}): ${result.stderr}\n$text")
        return text
    }

    private fun process(command: List<String>, timeoutMs: Long): AdbBytes {
        val proc = ProcessBuilder(command).start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = Thread { proc.inputStream.use { it.copyTo(stdout) } }
        val errThread = Thread { proc.errorStream.use { it.copyTo(stderr) } }
        outThread.start()
        errThread.start()
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            outThread.join(1_000)
            errThread.join(1_000)
            error("adb timed out: ${command.joinToString(" ")}")
        }
        outThread.join(5_000)
        errThread.join(5_000)
        return AdbBytes(proc.exitValue(), stdout.toByteArray(), stderr.toString(StandardCharsets.UTF_8))
    }

    private data class AdbBytes(val exit: Int, val stdout: ByteArray, val stderr: String)

    companion object {
        const val PACKAGE = "com.homecam.app"
        private const val INVITE = "files/gate-sec-invite.txt"
        private const val DIAGNOSTICS = "files/diagnostics/camera.jsonl"
        private val TEMPS = AtomicInteger()

        private fun sdkAdb(): String {
            val home = System.getenv("ANDROID_HOME") ?: error("ANDROID_HOME is not set")
            val adb = File(home, "platform-tools/adb")
            check(adb.isFile) { "sdk adb not found at ${adb.path}" }
            return adb.path
        }

        private val SQLITE = """
            import sqlite3, sys
            op, path = sys.argv[1], sys.argv[2]
            con = sqlite3.connect(path)
            con.execute("PRAGMA query_only=ON")
            if op == "count":
                print(con.execute("select count(*) from viewers").fetchone()[0])
            elif op == "endpoint":
                row = con.execute(
                    "select alertEndpoint from viewers where fingerprint = ?",
                    (sys.argv[3],),
                ).fetchone()
                print("" if row is None or row[0] is None else row[0])
            else:
                sys.exit(2)
        """.trimIndent() + "\n"
    }
}

internal class CpuSample(val percent: Double?, val line: String)
