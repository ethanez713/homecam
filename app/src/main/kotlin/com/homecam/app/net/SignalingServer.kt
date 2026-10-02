package com.homecam.app.net

import android.util.Log
import com.homecam.app.AppGraph
import com.homecam.app.camera.CameraNode
import com.homecam.app.camera.CameraServices
import com.homecam.app.camera.MediaController
import com.homecam.app.camera.RuntimeStatusProvider
import com.homecam.core.rpc.RpcPeer
import com.homecam.core.crypto.IdentityKey
import com.homecam.core.signaling.CameraHandshake
import com.homecam.core.signaling.ConnectionLimiter
import com.homecam.core.signaling.RejectionCoalescer
import com.homecam.proto.AuditEvent
import com.homecam.proto.Bye
import com.homecam.proto.Candidate
import com.homecam.proto.Presence
import com.homecam.proto.RpcMessage
import com.homecam.proto.Sdp
import com.homecam.proto.SignalMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.DefaultExtension
import org.java_websocket.protocols.Protocol
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The camera's signaling plane (§4.1): a TLS WebSocket serving its own pinned
 * certificate, in front of the mutual challenge-response that decides whether a
 * connection is allowed to exist at all.
 *
 * Everything above the handshake — SDP, ICE, and later the DataChannel — is reachable
 * only from a connection that already proved possession of an allowlisted identity key.
 */
class SignalingServer(
    private val graph: AppGraph,
    private val node: CameraNode,
    port: Int = Signaling.DEFAULT_PORT,
) : WebSocketServer(
    InetSocketAddress(port),
    Runtime.getRuntime().availableProcessors(),
    // The default draft's extension and protocol lists, which the handshake needs to match at all;
    // only the frame limit differs.
    listOf(Draft_6455(listOf(DefaultExtension()), listOf(Protocol("")), MAX_FRAME_BYTES)),
) {

    interface Listener {
        fun onAuthenticated(session: PeerSession)
        fun onOffer(session: PeerSession, sdp: Sdp)
        fun onAnswer(session: PeerSession, sdp: Sdp)
        fun onCandidate(session: PeerSession, candidate: Candidate)
        fun onClosed(session: PeerSession, reason: Bye.Reason)
    }

    var listener: Listener? = null

    val statusProvider = RuntimeStatusProvider(graph, node)

    /** Set by [com.homecam.app.camera.CameraService] once the capture pipeline is up. */
    var mediaController: MediaController? = null

    /** Set alongside [mediaController] once the recorder is up. */
    var clipStore: com.homecam.app.data.ClipStore? = null

    /**
     * One authenticated (or still-authenticating) connection. Messages are drained by a
     * single consumer coroutine so that handling stays in arrival order — a handshake
     * whose `auth` overtook its `hello` would fail for no reason.
     */
    inner class PeerSession(val socket: WebSocket) {
        val id: String = UUID.randomUUID().toString()
        val sourceIp: String = socket.remoteSocketAddress?.address?.hostAddress ?: "unknown"

        val handshake = CameraHandshake(
            signer = graph.identity,
            servedCertificateHash = graph.identity.certificateFingerprint,
            isAllowed = node::isAllowed,
            activePairing = node::activePairing,
            nowMs = System::currentTimeMillis,
        )

        var viewer: IdentityKey? = null
            private set
        var label: String = ""
            private set
        val authenticated: Boolean get() = viewer != null

        val openedAtMs: Long = System.currentTimeMillis()

        /**
         * Control RPC for this connection. `send` goes out over the DataChannel once one
         * is open and over this WebSocket until then, so reading status or the roster
         * never requires a media session (docs/DECISIONS.md D5).
         */
        val rpc: RpcPeer = RpcPeer { message ->
            val channel = dataChannelSender
            if (channel != null) channel(message)
            else send(SignalMessage.newBuilder().setRpc(message).build())
        }

        /** Set by the WebRTC layer at M4 once a DataChannel is open. */
        @Volatile var dataChannelSender: (suspend (RpcMessage) -> Unit)? = null

        internal val inbox = Channel<SignalMessage>(INBOX_CAPACITY)
        internal var pump: Job? = null

        fun send(message: SignalMessage) {
            if (socket.isOpen) runCatching { socket.send(message.toByteArray()) }
        }

        fun sendBye(reason: Bye.Reason, detail: String = "") {
            send(
                SignalMessage.newBuilder()
                    .setBye(Bye.newBuilder().setReason(reason).setDetail(detail))
                    .build()
            )
            socket.close()
        }

        internal fun accept(key: IdentityKey, deviceLabel: String) {
            viewer = key
            label = deviceLabel
        }
    }

    private val sessions = java.util.concurrent.ConcurrentHashMap<WebSocket, PeerSession>()
    private val limiter = ConnectionLimiter()
    private val rejections = RejectionCoalescer()
    private val scope: CoroutineScope get() = graph.scope

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
        setWebSocketFactory(DefaultSSLWebSocketServerFactory(PinnedTls.serverContext(graph.identity)))
        // A revoke has to reach a live connection, not just the next one (§2.6).
        node.onRevoked = { fingerprint -> disconnect(fingerprint, Bye.Reason.REVOKED) }
    }

    fun sessionOf(viewerFingerprint: String): PeerSession? =
        sessions.values.firstOrNull { it.viewer?.shortFingerprint == viewerFingerprint }

    fun disconnect(viewerFingerprint: String, reason: Bye.Reason) {
        sessions.values
            .filter { it.viewer?.shortFingerprint == viewerFingerprint }
            .forEach { it.sendBye(reason) }
    }

    fun broadcastPresence(state: Presence.State) {
        val message = SignalMessage.newBuilder()
            .setPresence(
                Presence.newBuilder().setState(state).setCameraAlias(graph.settings.current.alias)
            )
            .build()
        sessions.values.filter { it.authenticated }.forEach { it.send(message) }
    }

    override fun onStart() {
        Log.i(TAG, "signaling server listening on ${address.port}")
        scope.launch { graph.audit.record(AuditEvent.Kind.CAMERA_STARTED, detail = "port ${address.port}") }
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        if (handshake.resourceDescriptor != Signaling.PATH) {
            conn.close()
            return
        }
        val address = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
        if (!limiter.tryAdmit(address)) {
            noteRefusal(address, "limit", "too many connections")
            conn.close()
            return
        }
        val session = PeerSession(conn)
        sessions[conn] = session
        session.pump = scope.launch {
            for (message in session.inbox) handle(session, message)
        }
        // A peer that never completes the handshake holds a socket, a coroutine and a TLS
        // session for as long as connectionLostTimeout lets it; a real viewer is done in under a second.
        scope.launch {
            delay(PRE_AUTH_TIMEOUT_MS)
            if (sessions[conn] === session && !session.authenticated) reject(session, Bye.Reason.AUTH_FAILED, "timeout")
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // The protocol is binary protobuf; a text frame is not something a real peer sends.
        conn.close()
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val session = sessions[conn] ?: return
        val parsed = runCatching { SignalMessage.parseFrom(message) }.getOrNull()
        if (parsed == null) {
            reject(session, Bye.Reason.INCOMPATIBLE, "unparseable frame")
            return
        }
        if (session.inbox.trySend(parsed).isFailure) {
            // A peer sending faster than the handler drains is not behaving like a viewer.
            noteRefusal(session.sourceIp, "flood", "inbox full")
            session.socket.close()
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        val session = sessions.remove(conn) ?: return
        limiter.release(session.sourceIp)
        session.inbox.close()
        scope.launch { session.rpc.failAllPending("signaling channel closed") }
        session.pump?.cancel()
        if (session.authenticated) {
            listener?.onClosed(session, Bye.Reason.HANGUP)
            val duration = System.currentTimeMillis() - session.openedAtMs
            scope.launch {
                graph.audit.record(
                    AuditEvent.Kind.SESSION_CLOSED,
                    viewerFingerprint = session.viewer?.shortFingerprint.orEmpty(),
                    viewerLabel = session.label,
                    sourceIp = session.sourceIp,
                    detail = "${duration / 1000}s",
                )
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "signaling error", ex)
        if (conn == null) return
        sessions.remove(conn)?.let {
            limiter.release(it.sourceIp)
            it.socket.close()
        }
    }

    private suspend fun handle(session: PeerSession, message: SignalMessage) {
        when (message.bodyCase) {
            SignalMessage.BodyCase.HELLO -> onHello(session, message)
            SignalMessage.BodyCase.AUTH -> onAuth(session, message)
            SignalMessage.BodyCase.OFFER -> ifAuthenticated(session) { listener?.onOffer(it, message.offer) }
            SignalMessage.BodyCase.ANSWER -> ifAuthenticated(session) { listener?.onAnswer(it, message.answer) }
            SignalMessage.BodyCase.CANDIDATE -> ifAuthenticated(session) { listener?.onCandidate(it, message.candidate) }
            SignalMessage.BodyCase.RPC -> ifAuthenticated(session) { it.rpc.onMessage(message.rpc) }
            SignalMessage.BodyCase.BYE -> session.socket.close()
            // An unknown body from a newer viewer degrades rather than erroring (§4.2).
            else -> Unit
        }
    }

    private suspend fun onHello(session: PeerSession, message: SignalMessage) {
        when (val outcome = session.handshake.onHello(message.hello)) {
            is CameraHandshake.Outcome.Challenged ->
                session.send(SignalMessage.newBuilder().setChallenge(outcome.challenge).build())
            is CameraHandshake.Outcome.Rejected -> reject(session, outcome.reason, "hello")
            else -> reject(session, Bye.Reason.AUTH_FAILED, "hello")
        }
    }

    private suspend fun onAuth(session: PeerSession, message: SignalMessage) {
        when (val outcome = session.handshake.onAuth(message.auth)) {
            is CameraHandshake.Outcome.Accepted -> {
                if (outcome.pairedNow) {
                    node.completePairing(
                        outcome.viewer, outcome.label, session.sourceIp, outcome.verificationPhrase,
                    )
                } else {
                    graph.database.viewers()
                        .touch(outcome.viewer.shortFingerprint, System.currentTimeMillis())
                    graph.audit.record(
                        AuditEvent.Kind.SESSION_OPENED,
                        viewerFingerprint = outcome.viewer.shortFingerprint,
                        viewerLabel = outcome.label,
                        sourceIp = session.sourceIp,
                    )
                }
                session.accept(outcome.viewer, outcome.label)
                session.send(SignalMessage.newBuilder().setAuthResult(outcome.result).build())
                session.send(
                    SignalMessage.newBuilder()
                        .setPresence(
                            Presence.newBuilder()
                                .setState(Presence.State.AVAILABLE)
                                .setCameraAlias(graph.settings.current.alias)
                        )
                        .build()
                )
                CameraServices(
                    graph = graph,
                    node = node,
                    status = statusProvider,
                    media = mediaController,
                    clips = clipStore,
                    viewerLabel = outcome.label,
                    viewerFingerprint = outcome.viewer.shortFingerprint,
                    viewerIp = session.sourceIp,
                ).registerOn(session.rpc)
                listener?.onAuthenticated(session)
            }
            is CameraHandshake.Outcome.Rejected -> reject(session, outcome.reason, "auth")
            else -> reject(session, Bye.Reason.AUTH_FAILED, "auth")
        }
    }

    private suspend inline fun ifAuthenticated(session: PeerSession, block: (PeerSession) -> Unit) {
        if (session.authenticated) block(session) else session.sendBye(Bye.Reason.ACCESS_DENIED)
    }

    /**
     * Every refusal is logged — §2.7 counts a rejected attempt as an access event — but a
     * peer repeating the same refusal is one row and a count, so a flood cannot grow the
     * audit table or bury the rejection that matters.
     */
    private fun reject(session: PeerSession, reason: Bye.Reason, stage: String) {
        noteRefusal(session.sourceIp, stage, reason.name, session.viewer?.shortFingerprint.orEmpty())
        session.sendBye(reason, stage)
    }

    private fun noteRefusal(sourceIp: String, stage: String, reason: String, fingerprint: String = "") {
        val key = "$sourceIp|$stage"
        when (val decision = rejections.onReject(key, System.currentTimeMillis())) {
            RejectionCoalescer.Decision.Record -> scope.launch {
                graph.audit.record(
                    AuditEvent.Kind.CONNECTION_REJECTED,
                    viewerFingerprint = fingerprint,
                    sourceIp = sourceIp,
                    detail = "$stage: $reason",
                )
            }
            is RejectionCoalescer.Decision.Suppress -> if (decision.startsWindow) scope.launch {
                delay(rejections.windowDurationMs)
                val count = rejections.takeSuppressed(key)
                if (count > 0) graph.audit.record(
                    AuditEvent.Kind.CONNECTION_REJECTED,
                    sourceIp = sourceIp,
                    detail = "$stage: $reason, and $count more in ${rejections.windowDurationMs / 1000}s",
                )
            }
        }
    }

    private companion object {
        const val TAG = "SignalingServer"

        // The largest legitimate frame is an SDP or a 48 KB clip chunk; 256 KB leaves headroom.
        const val MAX_FRAME_BYTES = 256 * 1024
        const val INBOX_CAPACITY = 256
        const val PRE_AUTH_TIMEOUT_MS = 10_000L
    }
}
