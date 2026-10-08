package com.homecam.core.harness

import com.google.protobuf.ByteString
import com.homecam.core.crypto.JvmIdentitySigner
import com.homecam.core.signaling.CameraHandshake
import com.homecam.proto.AuditEvent
import com.homecam.proto.Hello
import com.homecam.proto.SignalMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Test
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * SEC.2. One hundred connections stay concurrent: [RawConnection.open] blocks, and
 * [Dispatchers.IO] will not run that many blocking calls at once.
 *
 * A door close is a peer close that delivered no frame. Admitted hellos get a bye first;
 * the connections past the per-address cap do not, and those are the sixth and later.
 */
class Sec2UnpairedFloodTest {

    @Test
    fun `flood from an unpaired peer`() = runBlocking {
        val guard = SafetyGuard()
        guard.run {
            val b = pair(SafetyGuard.LABEL_B)
            pair(SafetyGuard.LABEL_A)
            clientA?.drop()
            b.openSession()
            val head = AuditPages.headSeq(b)
            val marker = lastLine(adb.cameraJsonl())
            val pre = List(3) { adb.cpuSample().also { Thread.sleep(1_000) } }
            val samples = Collections.synchronizedList(mutableListOf<CpuSample>())
            val drops = Collections.synchronizedList(mutableListOf<String>())
            val door = AtomicInteger()
            val peerCloses = AtomicInteger()
            val sockets = Collections.synchronizedSet(mutableSetOf<RawConnection>())
            val pin = requirePin()
            var idleOk = false
            var idleEvidence = "idle socket was not opened"
            var frameOk = false
            var frameEvidence = "300 KB frame was not sent"

            val cameraHost = host
            val cameraPort = port
            val pool = Executors.newFixedThreadPool(WORKER_THREADS)
            val dispatcher = pool.asCoroutineDispatcher()
            try {
                coroutineScope {
                    val watch = launch(Dispatchers.IO) { this@run.watch(sockets) }
                    try {
                        val idle = openTracked(cameraHost, cameraPort, sockets, pin)
                        val idleAt = System.nanoTime()
                        val frame = openTracked(cameraHost, cameraPort, sockets, pin)
                        val floodStart = System.currentTimeMillis()
                        val deadline = floodStart + FLOOD_MS
                        val idleJob = async(Dispatchers.IO) {
                            val closed = idle.awaitPeerClose(IDLE_MAX_MS)
                            val elapsed = (System.nanoTime() - idleAt) / 1_000_000
                            idle.close()
                            sockets.remove(idle)
                            elapsed to closed
                        }
                        val frameJob = async(Dispatchers.IO) {
                            val sendError = runCatching { frame.send(ByteArray(FRAME_BYTES)) }.exceptionOrNull()
                            val closed = frame.awaitPeerClose(10_000)
                            frame.close()
                            sockets.remove(frame)
                            sendError to closed
                        }
                        val cpuJob = async(Dispatchers.IO) {
                            repeat(6) {
                                delay(10_000)
                                samples.add(adb.cpuSample())
                            }
                        }
                        val pollJob = async(Dispatchers.IO) {
                            while (System.currentTimeMillis() < deadline && isActive) {
                                try {
                                    val failure = when {
                                        !b.isConnected() -> "signaling down"
                                        !b.sessionStatus().active -> "session inactive"
                                        else -> null
                                    }
                                    if (failure != null) drops.add(failure)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    drops.add(e.message ?: e.javaClass.simpleName)
                                }
                                delay(5_000)
                            }
                        }
                        List(WORKERS) {
                            async(dispatcher) {
                                worker(cameraHost, cameraPort, pin, deadline, sockets, door, peerCloses)
                            }
                        }.awaitAll()
                        val (elapsed, idleClose) = idleJob.await()
                        idleOk = idleClose.byPeer && elapsed in IDLE_MIN_MS..IDLE_MAX_MS
                        idleEvidence = "closedByPeer=${idleClose.byPeer} closeCode=${idleClose.code} elapsedMs=$elapsed"
                        val (sendError, frameClose) = frameJob.await()
                        val headerCode = runCatching {
                            CameraSocket.oversizeHeaderProbe(cameraHost, cameraPort, pin, FRAME_BYTES)
                        }
                        // The full 300 KB write races the camera's reset, so the 1009 is read from a
                        // header-only probe; the full write must still end with the camera closing it.
                        frameOk = frameClose.byPeer && headerCode.getOrNull() == 1009
                        frameEvidence = "fullFrame: closedByPeer=${frameClose.byPeer} closeCode=${frameClose.code} " +
                            "send=${sendError?.javaClass?.simpleName ?: "ok"}; headerOnlyProbe: closeCode=${headerCode.getOrNull()} " +
                            "error=${headerCode.exceptionOrNull()?.message}"
                        cpuJob.await()
                        pollJob.await()
                        val quiet = floodStart + QUIET_MS - System.currentTimeMillis()
                        if (quiet > 0) delay(quiet)
                    } finally {
                        sockets.forEach { runCatching { it.close() } }
                        watch.cancel()
                    }
                }
            } catch (e: SafetyAbort) {
                if (e.reason.startsWith("camera_blind") || e.reason.startsWith("service_start")) {
                    assertPass(NO_RESTART, false, e.reason)
                }
                throw e
            } finally {
                dispatcher.close()
            }

            val jsonl = adb.cameraJsonl()
            val rows = AuditPages.after(b, head).filter { it.kind == AuditEvent.Kind.CONNECTION_REJECTED }
            val limitOwn = rows.count { !isSummary(it.detail) && it.detail.startsWith("limit:") }
            val verdicts = ArrayList<Pair<Boolean, String>>()
            fun verdict(line: String, ok: Boolean, evidence: String) {
                verdicts.add(ok to "${if (ok) "PASS" else "FAIL"}: $line\n$evidence")
            }
            val doorCloses = door.get()
            verdict(
                SIXTH,
                doorCloses >= 6 && limitOwn < doorCloses,
                "doorCloses=$doorCloses peerCloses=${peerCloses.get()} openFailures=${openFailures.get()} limitRows=$limitOwn",
            )
            verdict(FRAME, frameOk, frameEvidence)
            verdict(IDLE, idleOk, idleEvidence)

            val growth = growthBySourceMinute(rows)
            verdict(GROWTH, growth.ok, growth.evidence)
            val still = runCatching { b.isConnected() && b.sessionStatus().active }.getOrDefault(false)
            verdict(
                B_HOLDS,
                drops.isEmpty() && still,
                if (drops.isEmpty()) "active=$still" else drops.take(5).joinToString("; "),
            )
            val problem = freshProblem(jsonl)
            verdict(NO_RESTART, problem == null, problem ?: "no new camera_blind or service_start")
            verdict(CPU, cpuWithin(pre, samples), cpuEvidence(pre, samples))
            val gaps = linesAfter(jsonl, marker).mapNotNull { line ->
                val json = JsonLine(line)
                if (json.field("event") == "segment") json.long("gap_ms") else null
            }
            val gapEvidence = if (gaps.isEmpty()) {
                "no segment lines during the flood"
            } else {
                "max gap_ms=${gaps.maxOrNull()} lines=${gaps.size}"
            }
            verdict(GAP, gaps.all { it < 1_000L }, gapEvidence)
            org.junit.Assert.assertTrue(verdicts.joinToString("\n\n") { it.second }, verdicts.all { it.first })
        }
    }

    private suspend fun SafetyGuard.watch(sockets: MutableSet<RawConnection>) {
        var misses = 0
        while (coroutineContext.isActive) {
            try {
                val reason = freshProblem(adb.cameraJsonl())
                misses = 0
                if (reason != null) {
                    sockets.forEach { runCatching { it.close() } }
                    throw SafetyAbort(reason)
                }
            } catch (e: SafetyAbort) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                misses++
                if (misses >= 3) {
                    sockets.forEach { runCatching { it.close() } }
                    throw SafetyAbort("cannot read camera.jsonl: ${e.message}")
                }
            }
            delay(1_000)
        }
    }

    private fun openTracked(
        host: String,
        port: Int,
        sockets: MutableSet<RawConnection>,
        pin: ByteArray,
    ): RawConnection {
        val conn = RawConnection.open(host, port, pin, null)
        sockets.add(conn)
        return conn
    }

    private val openFailures = AtomicInteger()

    private suspend fun worker(
        host: String,
        port: Int,
        pin: ByteArray,
        deadline: Long,
        sockets: MutableSet<RawConnection>,
        door: AtomicInteger,
        peerCloses: AtomicInteger,
    ) {
        val signer = JvmIdentitySigner.generate()
        while (System.currentTimeMillis() < deadline && coroutineContext.isActive) {
            val conn = try {
                RawConnection.open(host, port, pin, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A camera shedding a flood stalls or drops handshakes on purpose.
                openFailures.incrementAndGet()
                delay(50)
                continue
            }
            sockets.add(conn)
            try {
                val sendError = runCatching { conn.send(unpairedHello(signer)) }.exceptionOrNull()
                val closed = conn.awaitPeerClose(5_000)
                if (closed.byPeer) {
                    peerCloses.incrementAndGet()
                    if (closed.frames == 0) door.incrementAndGet()
                } else if (sendError != null) {
                    openFailures.incrementAndGet()
                }
            } finally {
                conn.close()
                sockets.remove(conn)
            }
        }
    }

    private fun growthBySourceMinute(rows: List<AuditEvent>): Growth {
        if (rows.isEmpty()) return Growth(true, "CONNECTION_REJECTED rows=0")
        val origin = rows.minOf { it.atMs }
        val groups = rows.groupBy { it.sourceIp to ((it.atMs - origin) / 60_000) }
        val failures = ArrayList<String>()
        val lines = ArrayList<String>()
        for ((key, group) in groups) {
            val summaries = group.filter { isSummary(it.detail) }
            val own = group.size - summaries.size
            val sample = summaries.firstOrNull()?.detail ?: group.first().detail
            lines.add("${key.first} minute=${key.second} own=$own summary=${summaries.size} sample=${sample.take(120)}")
            // The limiter keys rejections by source and stage, so each stage may add its own summary.
            val stages = group.map { it.detail.substringBefore(':') }.toSet().size
            if (own > 6 || summaries.size > stages) failures.add("${key.first} minute=${key.second}")
        }
        return Growth(failures.isEmpty(), lines.joinToString("\n"))
    }

    // One `top` reading is noisy on a phone that is encoding video, so the verdict compares
    // medians. Refusing connections costs a TLS handshake each; the bound is that the camera
    // is not strained, not that the cost is zero.
    private fun cpuWithin(pre: List<CpuSample>, samples: List<CpuSample>): Boolean {
        val base = median(pre) ?: return false
        val during = median(samples) ?: return false
        return during - base <= MAX_MEDIAN_RISE_POINTS
    }

    private fun median(samples: List<CpuSample>): Double? {
        val values = samples.map { it.percent ?: return null }.sorted()
        if (values.isEmpty()) return null
        return values[values.size / 2]
    }

    private fun cpuEvidence(pre: List<CpuSample>, samples: List<CpuSample>): String = buildString {
        append("preMedian=").append(median(pre)).append(" duringMedian=").append(median(samples))
        append(" (limit +").append(MAX_MEDIAN_RISE_POINTS).append(")")
        pre.forEach { append("\npre ").append(it.percent).append(" (").append(it.line).append(")") }
        samples.forEach { append("\n").append(it.percent).append(" (").append(it.line).append(")") }
    }

    private class Growth(val ok: Boolean, val evidence: String)

    private companion object {
        const val WORKERS = 100
        const val WORKER_THREADS = 120
        const val FLOOD_MS = 60_000L
        const val QUIET_MS = 70_000L
        const val FRAME_BYTES = 300 * 1024
        // The pre-auth timeout is 10s. Shorter means the cap closed it; longer is the TCP timeout.
        const val IDLE_MIN_MS = 7_000L
        const val IDLE_MAX_MS = 20_000L

        val SUMMARY = Regex("and (\\d+) more")

        const val SIXTH = "the sixth-plus connection per source address is closed without an audit row of its own"
        const val FRAME = "the 300 KB frame closes that connection (1009)"
        const val IDLE = "an idle socket is closed by about 10 s"
        const val GROWTH =
            "CONNECTION_REJECTED rows grow by no more than 6 per source per minute plus one summary row carrying a count"
        const val B_HOLDS = "B's session never drops"
        const val NO_RESTART = "no camera_blind, no service_start"
        const val CPU =
            "the median homecam process CPU (adb shell top -b -n 1) during the flood is within 25 points of its pre-flood median"
        const val MAX_MEDIAN_RISE_POINTS = 25.0
        const val GAP = "the largest gap_ms of any segment line during the flood is under 1 s."

        fun isSummary(detail: String): Boolean = SUMMARY.containsMatchIn(detail)

        fun unpairedHello(signer: JvmIdentitySigner): ByteArray {
            val nonce = ByteArray(CameraHandshake.NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            val hello = Hello.newBuilder()
                .setViewerPublicKey(ByteString.copyFrom(signer.identity.spki))
                .setViewerNonce(ByteString.copyFrom(nonce))
                .setProtocolVersion(CameraHandshake.PROTOCOL_VERSION)
                .setPairing(false)
                .build()
            return SignalMessage.newBuilder().setHello(hello).build().toByteArray()
        }
    }
}
