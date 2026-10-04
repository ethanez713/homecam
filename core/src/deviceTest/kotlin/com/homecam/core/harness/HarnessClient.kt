package com.homecam.core.harness

import com.homecam.core.crypto.ConstantTime
import com.homecam.core.crypto.IdentityKey
import com.homecam.core.crypto.JvmIdentitySigner
import com.homecam.core.pairing.CameraPairingSession
import com.homecam.core.rpc.Controls
import com.homecam.core.rpc.RpcPeer
import com.homecam.core.signaling.ViewerHandshake
import com.google.protobuf.ByteString
import com.homecam.proto.AlertRegistration
import com.homecam.proto.AuditQuery
import com.homecam.proto.Bye
import com.homecam.proto.PairingInvite
import com.homecam.proto.RevokeViewer
import com.homecam.proto.RpcMessage
import com.homecam.proto.SessionConfig
import com.homecam.proto.SessionStatus
import com.homecam.proto.SignalMessage
import com.homecam.proto.ViewerList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Base64

/**
 * GATE-SEC viewer. The key is generated in this process for the run; it is never read
 * from a phone, and [ThrowawayViewer.LABEL] is not a handset's name.
 *
 * Built only with `-Pdevice.host` and `-Pdevice.port`, and not part of `:core:test`.
 * Arm pairing on the debug camera build, then pass the invite in:
 *
 * `adb shell am broadcast -n com.homecam.app/.debug.PairingArmReceiver -a com.homecam.app.debug.ARM_PAIRING`
 *
 * `adb exec-out run-as com.homecam.app cat files/gate-sec-invite.txt`
 *
 * `./gradlew :core:deviceTest -Pdevice.host=… -Pdevice.port=… -Pdevice.serial=… -Pdevice.inviteFile=…`
 *
 * A pin that is not in the invite is `-Pdevice.pin` (standard or URL-safe base64 of the
 * camera identity SPKI). The receiver is debug-only; after `./gradlew assembleRelease`
 * both of these print nothing (the second needs `$ANDROID_HOME/build-tools/<version>/aapt2`):
 * `grep -RIn -E 'PairingArmReceiver|ARM_PAIRING' app/build/intermediates/merged_manifest/release app/build/intermediates/merged_manifests/release app/build/intermediates/packaged_manifests/release`
 * `aapt2 dump xmltree app/build/outputs/apk/release/app-release-unsigned.apk --file AndroidManifest.xml | grep -E 'PairingArmReceiver|ARM_PAIRING'`
 */
class ThrowawayViewer(val signer: JvmIdentitySigner, val label: String) {
    val fingerprint: String get() = signer.identity.shortFingerprint

    companion object {
        const val LABEL = "gate-sec"

        fun generate(label: String = LABEL): ThrowawayViewer =
            ThrowawayViewer(JvmIdentitySigner.generate(), label)
    }
}

class HarnessRefused(val reason: Bye.Reason) : Exception("camera refused the connection: $reason")

class HarnessClient(
    private val host: String,
    private val port: Int,
    private val viewer: ThrowawayViewer,
    private val pin: ByteArray,
) {
    private var live: Live? = null

    /** Pairing hello/auth. The one-time token is signed into the transcript and not sent. */
    suspend fun pair(invite: PairingInvite): String {
        val key = invite.cameraPublicKey.toByteArray()
        check(ConstantTime.equals(pin, key)) { "pin does not match the invite" }
        return authenticate(invite.token.toByteArray())
    }

    /** Hello/auth as an already-paired viewer. */
    suspend fun connect(): String = authenticate(ByteArray(0))

    /**
     * Asks the camera to start a live session. Audio stays off: the runbook allows no
     * test sounds, and the mic is a separate act from opening video.
     */
    suspend fun openSession(): SessionStatus {
        val session = live ?: error("not connected")
        val payload = session.rpc.request(
            RpcMessage.Service.SESSION,
            Controls.Session.CONFIG,
            SessionConfig.newBuilder()
                .setVideo(true)
                .setAudio(false)
                .setMode(SessionConfig.Mode.LIVE)
                .build()
                .toByteString(),
            timeoutMs = SESSION_TIMEOUT_MS,
        )
        return SessionStatus.parseFrom(payload)
    }

    /** Revokes [fingerprint], defaulting to this throwaway viewer. */
    suspend fun revoke(fingerprint: String = viewer.fingerprint): Boolean {
        val session = live ?: error("not connected")
        return try {
            RevokeViewer.parseFrom(
                session.rpc.request(
                    RpcMessage.Service.ACCESS,
                    Controls.Access.REVOKE_VIEWER,
                    RevokeViewer.newBuilder().setFingerprint(fingerprint).build().toByteString(),
                )
            ).revoked
        } catch (e: Exception) {
            // Revoking this connection closes it from the camera side before the response
            // is written, so the bye is the acknowledgement.
            if (session.bye == Bye.Reason.REVOKED) true else throw e
        }
    }

    fun isConnected(): Boolean = live != null

    suspend fun viewers(): ViewerList = ViewerList.parseFrom(
        rpc(RpcMessage.Service.ACCESS, Controls.Access.VIEWER_LIST),
    )

    /** The live session this connection owns, or the default instance when it does not. */
    suspend fun sessionStatus(): SessionStatus = SessionStatus.parseFrom(
        rpc(RpcMessage.Service.STATUS, Controls.Status.SESSION),
    )

    suspend fun registerAlerts(registration: AlertRegistration) {
        rpc(
            RpcMessage.Service.ACCESS,
            Controls.Access.ALERT_REGISTRATION,
            registration.toByteString(),
        )
    }

    /**
     * One page of the audit log. [untilMs] of 0 asks the camera for its clock, which is
     * what the query handler already treats as "now".
     */
    suspend fun audit(sinceMs: Long, untilMs: Long, limit: Int): AuditQuery {
        val query = AuditQuery.newBuilder()
            .setSinceMs(sinceMs)
            .setUntilMs(untilMs)
            .setLimit(limit)
            .build()
        return AuditQuery.parseFrom(
            rpc(RpcMessage.Service.AUDIT, Controls.Audit.QUERY, query.toByteString(), AUDIT_TIMEOUT_MS),
        )
    }

    fun drop() {
        val session = live ?: return
        live = null
        runCatching {
            session.socket.send(
                SignalMessage.newBuilder().setBye(Bye.newBuilder().setReason(Bye.Reason.HANGUP)).build().toByteArray()
            )
        }
        session.socket.close()
        session.scope.cancel()
    }

    private suspend fun authenticate(pairingToken: ByteArray): String {
        check(live == null) { "already connected" }
        val camera = IdentityKey.parse(pin) ?: error("pin is not a P-256 identity key")
        val socket = withContext(Dispatchers.IO) { CameraSocket.open(host, port, pin, tlsProtocol = null) }
        val rpc = RpcPeer { message ->
            socket.send(SignalMessage.newBuilder().setRpc(message).build().toByteArray())
        }
        val session = Live(socket, rpc, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        live = session
        session.scope.launch { read(session) }
        try {
            val handshake = ViewerHandshake(
                signer = viewer.signer,
                expectedCamera = camera,
                servedCertificateHash = socket.servedCertificateHash,
                deviceLabel = viewer.label,
                pairingToken = pairingToken,
            )
            socket.send(SignalMessage.newBuilder().setHello(handshake.hello()).build().toByteArray())
            val challenged = next(session)
            if (challenged.bodyCase != SignalMessage.BodyCase.CHALLENGE) {
                throw HarnessRefused(Bye.Reason.AUTH_FAILED)
            }
            val phrase = when (val outcome = handshake.onChallenge(challenged.challenge)) {
                is ViewerHandshake.Outcome.Authenticating -> {
                    socket.send(SignalMessage.newBuilder().setAuth(outcome.auth).build().toByteArray())
                    outcome.verificationPhrase
                }
                is ViewerHandshake.Outcome.Rejected -> throw HarnessRefused(outcome.reason)
            }
            val result = next(session)
            if (result.bodyCase != SignalMessage.BodyCase.AUTH_RESULT) throw HarnessRefused(Bye.Reason.AUTH_FAILED)
            handshake.onAuthResult(result.authResult)?.let { throw HarnessRefused(it) }
            return phrase
        } catch (e: Exception) {
            drop()
            throw e
        }
    }

    private suspend fun rpc(
        service: RpcMessage.Service,
        control: Int,
        payload: ByteString = ByteString.EMPTY,
        timeoutMs: Long = RPC_TIMEOUT_MS,
    ): ByteString {
        val session = live ?: error("not connected")
        return session.rpc.request(service, control, payload, timeoutMs)
    }

    private suspend fun next(session: Live): SignalMessage {
        val message = withTimeout(HANDSHAKE_TIMEOUT_MS) { session.signals.receive() }
        if (message.bodyCase == SignalMessage.BodyCase.BYE) throw HarnessRefused(message.bye.reason)
        return message
    }

    private suspend fun read(session: Live) {
        try {
            while (true) {
                val frame = try {
                    session.socket.incoming.receive()
                } catch (e: ClosedReceiveChannelException) {
                    break
                }
                val message = runCatching { SignalMessage.parseFrom(frame) }.getOrElse {
                    session.socket.close()
                    break
                }
                when (message.bodyCase) {
                    SignalMessage.BodyCase.RPC -> session.rpc.onMessage(message.rpc)
                    SignalMessage.BodyCase.CHALLENGE, SignalMessage.BodyCase.AUTH_RESULT ->
                        if (!session.signals.trySend(message).isSuccess) session.socket.close()
                    SignalMessage.BodyCase.BYE -> {
                        session.bye = message.bye.reason
                        session.signals.trySend(message)
                        session.rpc.failAllPending("bye")
                    }
                    else -> Unit
                }
            }
        } finally {
            session.signals.close()
            session.rpc.failAllPending("closed")
        }
    }

    private class Live(
        val socket: CameraSocket,
        val rpc: RpcPeer,
        val scope: CoroutineScope,
        val signals: Channel<SignalMessage> = Channel(8),
    ) {
        @Volatile var bye: Bye.Reason? = null
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val SESSION_TIMEOUT_MS = 20_000L
        const val RPC_TIMEOUT_MS = 10_000L
        const val AUDIT_TIMEOUT_MS = 20_000L
    }
}

/** Address, invite, and pin for a `deviceTest` run. The pin is the property, else the invite. */
object DeviceConfig {
    fun host(): String = property("device.host")

    fun port(): Int = property("device.port").toInt()

    fun serial(): String = property("device.serial")

    fun invite(): PairingInvite {
        val inline = System.getProperty("device.invite")?.trim()?.takeIf { it.isNotEmpty() }
        val text = inline ?: System.getProperty("device.inviteFile")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { File(it).readText().trim() }
            ?: error("pass -Pdevice.invite or -Pdevice.inviteFile (files/gate-sec-invite.txt from the camera)")
        return decodeInvite(text)
    }

    /** The same decoder [invite] uses, for an invite the harness just pulled off the camera. */
    fun decodeInvite(text: String): PairingInvite =
        CameraPairingSession.decodeInvite(text.trim()) ?: error("invite did not decode")

    fun pin(invite: PairingInvite? = null): ByteArray {
        val property = System.getProperty("device.pin")?.trim()?.takeIf { it.isNotEmpty() }
        if (property != null) return decodePin(property)
        return invite?.cameraPublicKey?.toByteArray()?.takeIf { it.isNotEmpty() }
            ?: error("pass -Pdevice.pin or an invite")
    }

    private fun property(name: String): String =
        System.getProperty(name)?.takeIf { it.isNotBlank() } ?: error("missing $name")
}

fun decodePin(text: String): ByteArray = try {
    val trimmed = text.trim()
    try {
        Base64.getDecoder().decode(trimmed)
    } catch (e: IllegalArgumentException) {
        Base64.getUrlDecoder().decode(trimmed)
    }
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("device.pin is not base64", e)
}
