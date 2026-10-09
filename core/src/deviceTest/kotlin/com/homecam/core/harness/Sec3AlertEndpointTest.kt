package com.homecam.core.harness

import com.google.protobuf.ByteString
import com.homecam.core.crypto.AlertCrypto
import com.homecam.core.crypto.AlertEndpoint
import com.homecam.core.crypto.AlertLimits
import com.homecam.core.crypto.AlertPadding
import com.homecam.core.rpc.RpcRemoteException
import com.homecam.proto.AlertPayload
import com.homecam.proto.AlertRegistration
import com.homecam.proto.AuditEvent
import com.homecam.proto.RpcError
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** SEC.3. ntfy is read with [HttpURLConnection]: the deviceTest JVM turns hostname checks off for `java.net.http`. */
class Sec3AlertEndpointTest {

    @Test
    fun `alert endpoint policy`() = runBlocking {
        val guard = SafetyGuard()
        guard.run {
            val b = pair(SafetyGuard.LABEL_B)
            val a = pair(SafetyGuard.LABEL_A)
            val fingerprint = viewerB!!.fingerprint
            val storedBefore = adb.alertEndpoint(fingerprint)
            val rejects = ArrayList<String>()
            for (endpoint in BAD_ENDPOINTS) {
                val head = AuditPages.headSeq(b)
                val refused = AlertEndpoint.check(endpoint) as? AlertEndpoint.Result.Refused
                val error = runCatching {
                    b.registerAlerts(registration(rejectKey(), harnessTopic(), endpoint, enabled = true))
                }.exceptionOrNull()
                val remote = error as? RpcRemoteException
                val host = AlertEndpoint.hostOf(endpoint).ifBlank { "unparseable" }
                val detail = "alert endpoint refused (${refused?.reason}): $host"
                val row = AuditPages.after(b, head).any {
                    it.kind == AuditEvent.Kind.SETTINGS_CHANGED &&
                        it.viewerFingerprint == fingerprint &&
                        it.detail == detail
                }
                val stored = adb.alertEndpoint(fingerprint)
                val ok = remote?.code == RpcError.Code.INVALID_ARGUMENT && row && stored == storedBefore
                if (!ok) {
                    rejects.add(
                        "$endpoint code=${remote?.code} error=${error?.javaClass?.simpleName}:${error?.message} " +
                            "detail=$detail row=$row stored=$stored",
                    )
                }
            }
            assertPass(REFUSED, rejects.isEmpty(), if (rejects.isEmpty()) "stored=$storedBefore" else rejects.joinToString("\n"))

            val keys = AlertCrypto.generateAgreementKeyPair()
            val topic = harnessTopic()
            val subscription = NtfySubscription(topic)
            var evidence = "not subscribed"
            var published = false
            try {
                val subscribed = subscription.awaitOpen(20_000)
                if (!subscribed) {
                    evidence = "ntfy subscribe failed: ${subscription.error ?: "no open event"}"
                } else {
                    b.registerAlerts(
                        registration(
                            keys.public.encoded,
                            topic,
                            "https://ntfy.sh",
                            enabled = true,
                            sessionAlerts = true,
                            detectionAlerts = false,
                            healthAlerts = false,
                        ),
                    )
                    val marker = lastLine(adb.cameraJsonl())
                    if (!a.isConnected()) a.connect()
                    adb.unmuteHarnessAlerts()
                    a.openSession()
                    val body = subscription.awaitMessage(30_000)
                    val opened = body?.let { decrypt(keys, it) }
                    val jsonl = adb.cameraJsonl()
                    val delivered = linesAfter(jsonl, marker).mapNotNull { line ->
                        val json = JsonLine(line)
                        if (json.field("event") == "alert" && json.field("kind") == "session") {
                            "${json.field("delivered")}/${json.field("recipients")}"
                        } else {
                            null
                        }
                    }
                    // Every real roster member with alerts on is a recipient too, so the
                    // runbook's delivered=1 is "everyone it tried", not a literal 1.
                    published = opened != null && opened.bucket && delivered.any { pair ->
                        val (sent, wanted) = pair.split("/")
                        sent != "null" && sent != "0" && sent == wanted
                    }
                    evidence = "subscribed=true bodyBytes=${body?.trim()?.length} " +
                        "serialized=${opened?.serialized} padding=${opened?.padding} " +
                        "sealedBody=${opened?.sealedBody} bucket=${opened?.bucket} delivered=$delivered"
                }
            } finally {
                subscription.close()
            }
            assertPass(PUBLISHED, published, evidence)
        }
    }

    private fun registration(
        publicKey: ByteArray,
        topic: String,
        endpoint: String,
        enabled: Boolean,
        sessionAlerts: Boolean = false,
        detectionAlerts: Boolean = false,
        healthAlerts: Boolean = false,
    ): AlertRegistration = AlertRegistration.newBuilder()
        .setAlertPublicKey(ByteString.copyFrom(publicKey))
        .setTopic(topic)
        .setEndpoint(endpoint)
        .setEnabled(enabled)
        .setSessionAlerts(sessionAlerts)
        .setDetectionAlerts(detectionAlerts)
        .setHealthAlerts(healthAlerts)
        .build()

    private fun rejectKey(): ByteArray = AlertCrypto.generateAgreementKeyPair().public.encoded

    /** Bucket sizes are not public. A padded payload matches [AlertPadding.pad], and the body matches its sealed length. */
    private fun decrypt(keys: KeyPair, body: String): Opened? {
        val trimmed = body.trim()
        val sealed = runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull() ?: return null
        val plain = AlertCrypto.open(keys.private, keys.public.encoded, sealed) ?: return null
        val payload = runCatching { AlertPayload.parseFrom(plain) }.getOrNull() ?: return null
        val bare = payload.toBuilder().clearPadding().build()
        val fitted = AlertPadding.pad(bare)
        val sealedBody = AlertPadding.sealedBodySize(payload.serializedSize)
        return Opened(
            serialized = payload.serializedSize,
            padding = payload.padding.size(),
            sealedBody = sealedBody,
            bucket = fitted.serializedSize == payload.serializedSize &&
                payload.padding.size() > 0 &&
                trimmed.length == sealedBody &&
                trimmed.length <= AlertLimits.MAX_SEALED_BODY_BYTES,
        )
    }

    private class Opened(val serialized: Int, val padding: Int, val sealedBody: Int, val bucket: Boolean)

    private class NtfySubscription(private val topic: String) {
        private val lines = ArrayList<String>()
        private val connection = AtomicReference<HttpURLConnection?>(null)
        private val stop = AtomicBoolean(false)
        private val failure = AtomicReference<String?>(null)
        val error: String? get() = failure.get()
        private val thread = Thread {
            val conn = openStream() ?: return@Thread
            connection.set(conn)
            try {
                conn.inputStream.bufferedReader().use { reader ->
                    while (!stop.get()) {
                        val line = reader.readLine() ?: break
                        synchronized(lines) { lines.add(line) }
                    }
                }
            } catch (e: Exception) {
                if (!stop.get()) failure.compareAndSet(null, e.message)
            }
        }

        init {
            thread.isDaemon = true
            thread.name = "ntfy-gate-sec"
            thread.start()
        }

        suspend fun awaitOpen(timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (snapshot().any { JsonLine(it).field("event") == "open" }) return true
                if (failure.get() != null) return false
                delay(100)
            }
            return snapshot().any { JsonLine(it).field("event") == "open" }
        }

        suspend fun awaitMessage(timeoutMs: Long): String? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                messageBody()?.let { return it }
                delay(500)
            }
            return messageBody() ?: pollBody()
        }

        fun close() {
            stop.set(true)
            connection.get()?.disconnect()
            thread.join(2_000)
        }

        private fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

        private fun messageBody(): String? =
            snapshot().asReversed().firstNotNullOfOrNull { line ->
                val json = JsonLine(line)
                if (json.field("event") == "message") json.field("message") else null
            }

        private fun pollBody(): String? = runCatching {
            val conn = (URL("$ROOT/$topic/json?poll=1&since=all").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()?.lineSequence()?.mapNotNull { line ->
            val json = JsonLine(line)
            if (json.field("event") == "message") json.field("message") else null
        }?.lastOrNull()

        private fun openStream(): HttpURLConnection? = try {
            (URL("$ROOT/$topic/json").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 0
            }
        } catch (e: Exception) {
            failure.compareAndSet(null, e.message)
            null
        }
    }

    private companion object {
        const val ROOT = "https://ntfy.sh"
        val BAD_ENDPOINTS = listOf(
            "http://ntfy.sh",
            "https://user:pw@ntfy.sh",
            "https://ntfy.sh/?x=1",
            "ftp://ntfy.sh",
        )
        const val REFUSED =
            "each is answered INVALID_ARGUMENT and leaves an audit row naming the reason and host; " +
                "the stored endpoint is unchanged."
        const val PUBLISHED =
            "the alert is published, the harness (subscribed to its own topic on ntfy.sh) decrypts it, " +
                "its body size is one of the padding buckets, and the camera's alert line reports delivered=1"

        fun harnessTopic(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return "hc-" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
