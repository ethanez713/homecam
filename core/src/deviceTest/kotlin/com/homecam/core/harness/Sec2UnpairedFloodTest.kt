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
import kotlin.math.abs

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
            val pre = adb.cpuSample()
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
                        frameOk = frameClose.byPeer && frameClose.code == 1009
                        frameEvidence = "closedByPeer=${frameClose.byPeer} closeCode=${frameClose.code} " +
                            "send=${sendError?.javaClass?.simpleName ?: "ok"}"
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
            val doorCloses = door.get()
            assertPass(
                SIXTH,
                doorCloses >= 6 && limitOwn < doorCloses,
                "doorCloses=$doorCloses peerCloses=${peerCloses.get()} limitRows=$limitOwn",
            )
            assertPass(FRAME, frameOk, frameEvidence)
            assertPass(IDLE, idleOk, idleEvidence)

            val growth = growthBySourceMinute(rows)
            assertPass(GROWTH, growth.ok, growth.evidence)
            val still = runCatching { b.isConnected() && b.sessionStatus().active }.getOrDefault(false)
            assertPass(
                B_HOLDS,
                drops.isEmpty() && still,
                if (drops.isEmpty()) "active=$still" else drops.take(5).joinToString("; "),
            )
            val problem = freshProblem(jsonl)
            assertPass(NO_RESTART, problem == null, problem ?: "no new camera_blind or service_start")
            assertPass(CPU, cpuWithin(pre, samples), cpuEvidence(pre, samples))
            val gaps = linesAfter(jsonl, marker).mapNotNull { line ->
                val json = JsonLine(line)
                if (json.field("event") == "segment") json.long("gap_ms") else null
            }
            val gapEvidence = if (gaps.isEmpty()) {
                "no segment lines during the flood"
            } else {
                "max gap_ms=${gaps.maxOrNull()} lines=${gaps.size}"
            }
            assertPass(GAP, gaps.all { it < 1_000L }, gapEvidence)
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
            }
            sockets.add(conn)
            try {
                val sendError = runCatching { conn.send(unpairedHello(signer)) }.exceptionOrNull()
                val closed = conn.awaitPeerClose(5_000)
                if (closed.byPeer) {
                    peerCloses.incrementAndGet()
                    if (closed.frames == 0) door.incrementAndGet()
                } else if (sendError != null) {
                    throw sendError
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
            if (own > 6 || summaries.size > 1) failures.add("${key.first} minute=${key.second}")
        }
        return Growth(failures.isEmpty(), lines.joinToString("\n"))
    }

    private fun cpuWithin(pre: CpuSample, samples: List<CpuSample>): Boolean {
        val base = pre.percent ?: return false
        return samples.isNotEmpty() && samples.all { sample ->
            val value = sample.percent ?: return false
            abs(value - base) <= 15.0
        }
    }

    private fun cpuEvidence(pre: CpuSample, samples: List<CpuSample>): String {
        val body = buildString {
            append("pre=").append(pre.percent).append(" (").append(pre.line).append(")")
            samples.forEach { append("\n").append(it.percent).append(" (").append(it.line).append(")") }
        }
        return body
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
            "the homecam process CPU (adb shell top -b -n 1) stays within 15 points of its pre-flood value"
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
