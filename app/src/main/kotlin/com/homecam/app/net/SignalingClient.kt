package com.homecam.app.net

import android.util.Log
import com.homecam.app.crypto.DeviceIdentity
import com.homecam.core.crypto.IdentityKey
import com.homecam.core.signaling.CameraHandshake
import com.homecam.core.signaling.ViewerHandshake
import com.homecam.proto.Bye
import com.homecam.proto.Candidate
import com.homecam.proto.Presence
import com.homecam.proto.Sdp
import com.homecam.proto.SignalMessage
import kotlinx.coroutines.CompletableDeferred
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.security.MessageDigest
import java.nio.ByteBuffer

/**
 * The viewer's end of the signaling plane. Two things must both hold before this is a
 * connection worth using: the TLS certificate matches the one pinned at pairing time, and
 * the camera signs a transcript that binds that same certificate to its identity key.
 */
class SignalingClient(
    private val identity: DeviceIdentity,
    private val camera: IdentityKey,
    private val deviceLabel: String,
    host: String,
    port: Int,
    private val pairingToken: ByteArray = ByteArray(0),
) {
    interface Listener {
        fun onAuthenticated(verificationPhrase: String)
        fun onPresence(presence: Presence)
        fun onOffer(sdp: Sdp)
        fun onAnswer(sdp: Sdp)
        fun onCandidate(candidate: Candidate)
        fun onClosed(reason: Bye.Reason, detail: String)
    }

    var listener: Listener? = null

    /**
     * Built in `onOpen` rather than up front, because the transcript binds the hash of the
     * certificate this connection actually received — which does not exist until TLS has
     * completed.
     */
    private lateinit var handshake: ViewerHandshake

    @Volatile private var servedCertificateHash: ByteArray? = null

    private val authenticated = CompletableDeferred<String>()
    private var closeReason: Bye.Reason = Bye.Reason.UNSPECIFIED

    private val socket = object : WebSocketClient(URI("wss://$host:$port${Signaling.PATH}")) {
        override fun onOpen(handshakedata: ServerHandshake) {
            val bound = servedCertificateHash
            if (bound == null) {
                closeReason = Bye.Reason.AUTH_FAILED
                close()
                return
            }
            handshake = ViewerHandshake(
                signer = identity,
                expectedCamera = camera,
                servedCertificateHash = bound,
                deviceLabel = deviceLabel,
                pairingToken = pairingToken,
            )
            send(SignalMessage.newBuilder().setHello(handshake.hello()).build().toByteArray())
        }

        override fun onMessage(message: String) = close()

        override fun onMessage(bytes: ByteBuffer) {
            val parsed = runCatching { SignalMessage.parseFrom(bytes) }.getOrNull() ?: return close()
            dispatch(parsed)
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            if (!authenticated.isCompleted) {
                authenticated.completeExceptionally(
                    SignalingRefused(closeReason.takeIf { it != Bye.Reason.UNSPECIFIED } ?: Bye.Reason.HANGUP)
                )
            }
            listener?.onClosed(closeReason, reason.orEmpty())
        }

        override fun onError(ex: Exception) {
            Log.w(TAG, "signaling client error", ex)
        }
    }

    init {
        val context = PinnedTls.clientContext(camera.spki) { certificate ->
            servedCertificateHash = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        }
        socket.setSocketFactory(context.socketFactory)
        socket.connectionLostTimeout = 30
    }

    /** Connects, completes the mutual handshake, and returns the verification phrase. */
    suspend fun connect(): String {
        socket.connect()
        return authenticated.await()
    }

    fun send(message: SignalMessage) {
        if (socket.isOpen) runCatching { socket.send(message.toByteArray()) }
    }

    fun close(reason: Bye.Reason = Bye.Reason.HANGUP) {
        send(SignalMessage.newBuilder().setBye(Bye.newBuilder().setReason(reason)).build())
        socket.close()
    }

    private fun dispatch(message: SignalMessage) {
        when (message.bodyCase) {
            SignalMessage.BodyCase.CHALLENGE -> onChallenge(message)
            SignalMessage.BodyCase.AUTH_RESULT -> onAuthResult(message)
            SignalMessage.BodyCase.PRESENCE -> listener?.onPresence(message.presence)
            SignalMessage.BodyCase.OFFER -> listener?.onOffer(message.offer)
            SignalMessage.BodyCase.ANSWER -> listener?.onAnswer(message.answer)
            SignalMessage.BodyCase.CANDIDATE -> listener?.onCandidate(message.candidate)
            SignalMessage.BodyCase.BYE -> {
                closeReason = message.bye.reason
                socket.close()
            }
            else -> Unit
        }
    }

    /** Available once [connect] has returned; shown alongside a live session. */
    var verificationPhrase: String? = null
        private set

    private var pendingPhrase: String? = null

    private fun onChallenge(message: SignalMessage) {
        when (val outcome = handshake.onChallenge(message.challenge)) {
            is ViewerHandshake.Outcome.Authenticating -> {
                pendingPhrase = outcome.verificationPhrase
                socket.send(SignalMessage.newBuilder().setAuth(outcome.auth).build().toByteArray())
            }
            is ViewerHandshake.Outcome.Rejected -> {
                closeReason = outcome.reason
                socket.close()
            }
        }
    }

    private fun onAuthResult(message: SignalMessage) {
        val refusal = handshake.onAuthResult(message.authResult)
        if (refusal != null) {
            closeReason = refusal
            socket.close()
            return
        }
        // The phrase is derived locally from the transcript; the camera echoes its own so
        // a mismatch shows up as two different codes on two screens rather than silence.
        val phrase = pendingPhrase ?: message.authResult.verificationPhrase
        verificationPhrase = phrase
        authenticated.complete(phrase)
        listener?.onAuthenticated(phrase)
    }

    val protocolVersion: Int get() = CameraHandshake.PROTOCOL_VERSION

    private companion object { const val TAG = "SignalingClient" }
}

class SignalingRefused(val reason: Bye.Reason) : Exception("camera refused the connection: $reason")
