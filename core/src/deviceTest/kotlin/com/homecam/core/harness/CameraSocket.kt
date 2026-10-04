package com.homecam.core.harness

import com.homecam.core.crypto.ConstantTime
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.ProxySelector
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.io.ByteArrayOutputStream

/**
 * TLS and WebSocket to a camera, with no dependency beyond the JDK.
 *
 * The camera certificate is `CN=homecam` served on an address (`DeviceIdentity`), so it
 * can never satisfy a hostname check. The SPKI pin is the check (D4). `java.net.http`
 * overwrites [SSLParameters.endpointIdentificationAlgorithm] with `HTTPS` unless
 * `jdk.internal.httpclient.disableHostnameVerification` is already set when its internal
 * `Utils` class loads — the `deviceTest` task sets it on the JVM for that reason.
 * The flag covers every `java.net.http` client in the process; a later check that must
 * verify a public hostname has to use a different HTTP stack.
 *
 * Each [open] builds its own client. `HttpClient` pools idle TLS sessions, and a reused
 * session would not be a new connection or a new negotiated protocol.
 */
internal class CameraSocket private constructor(
    private val webSocket: WebSocket,
    val incoming: Channel<ByteArray>,
    val servedCertificateHash: ByteArray,
    private val closeState: CloseState,
) {
    private val closed = AtomicBoolean(false)

    /** First peer close code, or -1 when the peer has not closed. Our own close does not count. */
    val peerCloseCode: Int get() = if (closeState.peer.get()) closeState.code.get() else -1

    val closedByPeer: Boolean get() = closeState.peer.get()

    fun send(bytes: ByteArray) {
        webSocket.sendBinary(ByteBuffer.wrap(bytes.copyOf()), true).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Set before sendClose so the close frame we generate is not recorded as the peer's.
        closeState.local.set(true)
        incoming.close()
        runCatching { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS) }
        runCatching { webSocket.abort() }
    }

    companion object {
        /** The camera's signaling path (`com.homecam.app.net.Signaling`). */
        const val PATH = "/hc"

        fun open(host: String, port: Int, pin: ByteArray, tlsProtocol: String?): CameraSocket {
            val served = AtomicReference<ByteArray>()
            val context = sslContext(pin) { certificate ->
                served.set(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
            }
            val parameters = SSLParameters().apply {
                endpointIdentificationAlgorithm = ""
                if (tlsProtocol != null) protocols = arrayOf(tlsProtocol)
            }
            val incoming = Channel<ByteArray>(INBOUND_CAPACITY)
            val closeState = CloseState()
            val http = HttpClient.newBuilder()
                .sslContext(context)
                .sslParameters(parameters)
                .proxy(ProxySelector.of(null))
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build()
            val socket = try {
                http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                    .buildAsync(signalingUri(host, port), Frames(incoming, closeState))
                    .get(CONNECT_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                incoming.close()
                throw unwrapPinFailure(e)
            }
            val hash = served.get()
            if (hash == null) {
                incoming.close()
                runCatching { socket.abort() }
                error("pin check did not see a certificate")
            }
            return CameraSocket(socket, incoming, hash, closeState)
        }

        private fun sslContext(pin: ByteArray, onCertificate: (X509Certificate) -> Unit): SSLContext {
            val trust = object : X509ExtendedTrustManager() {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                    throw CertificateException("this context is never a server")

                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) =
                    throw CertificateException("this context is never a server")

                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
                    throw CertificateException("this context is never a server")

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = accept(chain)

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) =
                    accept(chain)

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
                    accept(chain)

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                private fun accept(chain: Array<out X509Certificate>?) {
                    val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate offered")
                    if (!ConstantTime.equals(pin, leaf.publicKey.encoded)) {
                        throw CertificateException("certificate does not match the pinned camera")
                    }
                    onCertificate(leaf)
                }
            }
            return SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trust), SecureRandom())
            }
        }

        private fun signalingUri(host: String, port: Int): URI {
            val name = if (':' in host && !host.startsWith("[")) "[$host]" else host
            return URI("wss://$name:$port$PATH")
        }

        private fun unwrapPinFailure(error: Exception): Exception {
            val cause = generateSequence<Throwable>(error) { it.cause }
                .firstOrNull { it is CertificateException }
            if (cause != null) return CertificateException(cause.message).apply { initCause(error) }
            if (error is ExecutionException && error.cause is Exception) return error.cause as Exception
            return error
        }

        private const val HOSTNAME_PROPERTY = "jdk.internal.httpclient.disableHostnameVerification"
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val SEND_TIMEOUT_SECONDS = 10L
        private const val INBOUND_CAPACITY = 256

        init {
            // Before any HttpClient use. Too late if Utils has already been loaded; the
            // deviceTest task sets the same property on the JVM for that reason.
            System.setProperty(HOSTNAME_PROPERTY, "true")
        }
    }
}

/**
 * A TLS+WebSocket connection that does not run the handshake. [tlsProtocol] is `TLSv1.3`
 * or `TLSv1.2` (SEC.4); null leaves both enabled. A [pin] that is not the camera's
 * identity SPKI fails inside the client's pin check, before any frame is sent.
 */
class RawConnection internal constructor(private val socket: CameraSocket) : AutoCloseable {

    private val sent = AtomicInteger(0)

    /** Application messages handed to [send]. The TLS handshake is not one of them. */
    val messagesSent: Int get() = sent.get()

    fun send(bytes: ByteArray) {
        sent.incrementAndGet()
        sentTotal.incrementAndGet()
        socket.send(bytes)
    }

    val closeCode: Int get() = socket.peerCloseCode

    val closedByPeer: Boolean get() = socket.closedByPeer

    /**
     * The next binary frame, or null when the peer closes or [timeoutMs] elapses.
     * Suspends instead of blocking a thread: a flood holds one of these per connection.
     */
    suspend fun receive(timeoutMs: Long): ByteArray? = withTimeoutOrNull(timeoutMs) {
        try {
            socket.incoming.receive()
        } catch (e: ClosedReceiveChannelException) {
            null
        }
    }

    /** Waits until the peer closes. Frames received along the way are counted and discarded. */
    suspend fun awaitPeerClose(timeoutMs: Long): PeerClose {
        var frames = 0
        withTimeoutOrNull(timeoutMs) {
            while (!socket.closedByPeer) {
                try {
                    socket.incoming.receive()
                    frames++
                } catch (e: ClosedReceiveChannelException) {
                    break
                }
            }
        }
        return PeerClose(
            code = socket.peerCloseCode,
            frames = frames,
            byPeer = socket.closedByPeer,
        )
    }

    override fun close() = socket.close()

    companion object {
        private val sentTotal = AtomicInteger(0)

        /** How many times [send] has been called in this process. A pin failure never increments it. */
        fun applicationMessagesSent(): Int = sentTotal.get()

        fun open(host: String, port: Int, pin: ByteArray, tlsProtocol: String? = null): RawConnection =
            RawConnection(CameraSocket.open(host, port, pin, tlsProtocol))
    }
}

/** What the peer did, as opposed to a close this process initiated. */
class PeerClose(val code: Int, val frames: Int, val byPeer: Boolean)

/**
 * [code] keeps the first peer status. A later close of our own must not replace a 1009.
 * [local] is set before we send our own close, so that frame is not mistaken for the peer.
 */
private class CloseState {
    val code = AtomicInteger(-1)
    val local = AtomicBoolean(false)
    val peer = AtomicBoolean(false)
}

private class Frames(
    private val incoming: Channel<ByteArray>,
    private val closeState: CloseState,
) : WebSocket.Listener {
    private val partial = ByteArrayOutputStream()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        val bytes = ByteArray(data.remaining())
        data.get(bytes)
        if (partial.size() + bytes.size > MAX_MESSAGE_BYTES) {
            incoming.close(IllegalStateException("frame exceeds $MAX_MESSAGE_BYTES"))
            webSocket.abort()
            return null
        }
        partial.write(bytes)
        if (last) {
            val message = partial.toByteArray()
            partial.reset()
            if (!incoming.trySend(message).isSuccess) {
                incoming.close(IllegalStateException("inbound queue full"))
                webSocket.abort()
                return null
            }
        }
        webSocket.request(1)
        return null
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        incoming.close(IllegalStateException("text frame"))
        webSocket.abort()
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        if (!closeState.local.get()) {
            closeState.peer.set(true)
            closeState.code.compareAndSet(-1, statusCode)
        }
        incoming.close()
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        if (!closeState.local.get()) closeState.peer.set(true)
        incoming.close(error)
    }

    private companion object {
        const val MAX_MESSAGE_BYTES = 1024 * 1024
    }
}
