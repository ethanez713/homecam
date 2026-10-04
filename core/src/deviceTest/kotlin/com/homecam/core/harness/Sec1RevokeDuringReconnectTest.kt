package com.homecam.core.harness

import com.homecam.proto.AuditEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ThreadLocalRandom

/**
 * SEC.1. B stays on its signaling connection and does not open video: A's `openSession`
 * replaces whoever holds the live session and closes that peer, which would drop B by design.
 */
class Sec1RevokeDuringReconnectTest {

    @Test
    fun `revoke during reconnect`() = runBlocking {
        val guard = SafetyGuard()
        guard.run {
            val b = pair(SafetyGuard.LABEL_B)
            val bFingerprint = viewerB!!.fingerprint
            val started = AuditPages.headSeq(b)
            repeat(RUNS) { index ->
                val run = index + 1
                val a = pair(SafetyGuard.LABEL_A)
                val fingerprint = viewerA!!.fingerprint
                val head = AuditPages.headSeq(b)
                a.drop()
                val stop = AtomicBoolean(false)
                val revoked = AtomicBoolean(false)
                val offset = ThreadLocalRandom.current().nextLong(0, 1_501)
                val extra = ThreadLocalRandom.current().nextLong(200, 601)
                coroutineScope {
                    val loop = launch {
                        while (!stop.get()) {
                            try {
                                a.connect()
                                a.openSession()
                                a.drop()
                            } catch (e: CancellationException) {
                                a.drop()
                                throw e
                            } catch (e: Exception) {
                                a.drop()
                                if (!revoked.get()) throw e
                            }
                        }
                    }
                    delay(offset)
                    revoked.set(true)
                    b.revoke(fingerprint)
                    delay(extra)
                    stop.set(true)
                    loop.join()
                }
                a.drop()
                val stillThere = runCatching { b.viewers() }.getOrElse {
                    assertPass(B_UNAFFECTED, false, "run $run: B's RPC failed: ${it.message}")
                    return@repeat
                }
                assertPass(
                    B_UNAFFECTED,
                    b.isConnected() && stillThere.viewersList.any { it.fingerprint == bFingerprint },
                    "run $run: connected=${b.isConnected()} roster has B=" +
                        stillThere.viewersList.any { it.fingerprint == bFingerprint },
                )
                val events = AuditPages.after(b, head).filter { it.viewerFingerprint == fingerprint }
                val revokedSeq = events.filter { it.kind == AuditEvent.Kind.VIEWER_REVOKED }.maxOfOrNull { it.seq }
                val offending = events.filter {
                    (it.kind == AuditEvent.Kind.SESSION_OPENED || it.kind == AuditEvent.Kind.PAIRING_COMPLETED) &&
                        revokedSeq != null && it.seq > revokedSeq
                }
                val evidence = buildString {
                    append("run $run fingerprint=$fingerprint offsetMs=$offset extraMs=$extra")
                    append(" revokedSeq=$revokedSeq")
                    if (offending.isEmpty() && revokedSeq != null) {
                        append(" offending=0")
                    } else {
                        append('\n')
                        events.take(20).forEach { append(it.kind).append(" seq=").append(it.seq).append('\n') }
                    }
                }
                assertPass(NO_SESSION_AFTER_REVOKE, revokedSeq != null && offending.isEmpty(), evidence)
            }
            val events = AuditPages.after(b, started)
            val closed = events.filter {
                it.viewerFingerprint == bFingerprint && it.kind == AuditEvent.Kind.SESSION_CLOSED
            }
            val revokedB = events.filter {
                it.viewerFingerprint == bFingerprint && it.kind == AuditEvent.Kind.VIEWER_REVOKED
            }
            val status = b.openSession()
            val roster = b.viewers()
            assertPass(
                B_UNAFFECTED,
                b.isConnected() &&
                    roster.viewersList.any { it.fingerprint == bFingerprint } &&
                    closed.isEmpty() &&
                    revokedB.isEmpty() &&
                    status.active,
                "connected=${b.isConnected()} active=${status.active} " +
                    "sessionClosed=${closed.size} viewerRevoked=${revokedB.size}",
            )
        }
    }

    private companion object {
        const val RUNS = 200
        const val NO_SESSION_AFTER_REVOKE =
            "no SESSION_OPENED or PAIRING_COMPLETED for A's fingerprint has a seq above its " +
                "VIEWER_REVOKED row, in any run"
        const val B_UNAFFECTED = "B's own session is unaffected."
    }
}
