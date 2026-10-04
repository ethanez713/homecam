package com.homecam.core.harness

import com.homecam.core.crypto.ConstantTime
import com.homecam.core.crypto.JvmIdentitySigner
import com.homecam.core.signaling.CameraHandshake
import com.homecam.core.signaling.ViewerHandshake
import com.homecam.proto.Bye
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.security.cert.CertificateException

/** SEC.4. The fake camera never opens a socket; the pin check has to fail before [RawConnection.send]. */
class Sec4TlsPinTest {

    @Test
    fun `tls versions, the pin, and a fake camera`() = runBlocking {
        val guard = SafetyGuard()
        guard.run {
            pair(SafetyGuard.LABEL_B)
            pair(SafetyGuard.LABEL_A)
            clientA?.drop()
            clientB?.drop()
            val pin = requirePin()

            val before13 = lastLine(adb.cameraJsonl())
            val tls13 = RawConnection.open(host, port, pin, "TLSv1.3")
            val line13 = try {
                awaitTls(before13, "TLSv1.3")
            } finally {
                tls13.close()
            }
            val before12 = lastLine(adb.cameraJsonl())
            val tls12 = RawConnection.open(host, port, pin, "TLSv1.2")
            val line12 = try {
                awaitTls(before12, "TLSv1.2")
            } finally {
                tls12.close()
            }
            assertPass(
                HANDSHAKE,
                line13 != null && line12 != null,
                "TLSv1.3=${line13 ?: "missing"} TLSv1.2=${line12 ?: "missing"}",
            )

            val wrong = generateWrongPin(pin)
            val sentBefore = RawConnection.applicationMessagesSent()
            var opened: RawConnection? = null
            val caught = try {
                opened = RawConnection.open(host, port, wrong, null)
                null
            } catch (e: CertificateException) {
                e
            } finally {
                opened?.close()
            }
            val sentAfter = RawConnection.applicationMessagesSent()
            val pinned = JvmIdentitySigner.generate()
            val imposter = JvmIdentitySigner.generate()
            val viewer = JvmIdentitySigner.generate()
            val camera = CameraHandshake(
                signer = imposter,
                servedCertificateHash = ByteArray(32),
                isAllowed = { true },
                activePairing = { null },
                nowMs = { 0L },
            )
            val handshake = ViewerHandshake(
                signer = viewer,
                expectedCamera = pinned.identity,
                servedCertificateHash = ByteArray(32),
                deviceLabel = "gate-sec-fake",
            )
            val outcome = when (val hello = camera.onHello(handshake.hello())) {
                is CameraHandshake.Outcome.Challenged -> handshake.onChallenge(hello.challenge)
                else -> null
            }
            val pinRefused = opened == null &&
                caught?.message == PIN_MISMATCH &&
                sentBefore == sentAfter
            val fakeRefused = outcome is ViewerHandshake.Outcome.Rejected &&
                outcome.reason == Bye.Reason.ACCESS_DENIED
            assertPass(
                PIN_AND_FAKE,
                pinRefused && fakeRefused,
                "pinException=${caught?.javaClass?.simpleName}:${caught?.message} " +
                    "connectionOpened=${opened != null} messagesSent ${sentBefore}->${sentAfter} " +
                    "viewerOutcome=${outcome?.let { it::class.simpleName } ?: "no challenge"}",
            )
        }
    }

    private suspend fun SafetyGuard.awaitTls(marker: String?, protocol: String): String? {
        repeat(8) {
            val match = linesAfter(adb.cameraJsonl(), marker).firstOrNull { line ->
                val json = JsonLine(line)
                json.field("event") == "tls" &&
                    json.field("protocol") == protocol &&
                    json.field("outcome") == "accepted"
            }
            if (match != null) return match
            delay(500)
        }
        return null
    }

    private fun generateWrongPin(pin: ByteArray): ByteArray {
        val wrong = JvmIdentitySigner.generate().identity.spki
        check(!ConstantTime.equals(pin, wrong)) { "generated pin matched the camera" }
        return wrong
    }

    private companion object {
        const val PIN_MISMATCH = "certificate does not match the pinned camera"
        const val HANDSHAKE =
            "the first two complete the handshake and the camera's tls lines record TLSv1.3 and TLSv1.2"
        const val PIN_AND_FAKE =
            "the third is refused by the client's own pin check before any message is sent, " +
                "and a fake camera in the harness presenting a different identity key is refused by ViewerHandshake"
    }
}
