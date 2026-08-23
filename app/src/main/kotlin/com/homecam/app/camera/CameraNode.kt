package com.homecam.app.camera

import com.homecam.app.AppGraph
import com.homecam.app.data.ViewerEntity
import com.homecam.app.net.LocalAddress
import com.homecam.core.crypto.IdentityKey
import com.homecam.core.pairing.CameraPairingSession
import com.homecam.proto.AuditEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the camera role owns that outlives a screen: the allowlist, the pairing
 * window, and the audit trail those two produce.
 */
class CameraNode(private val graph: AppGraph) {

    private val viewers = graph.database.viewers()

    /**
     * The handshake runs on a socket thread and has to answer "is this key allowed?"
     * without suspending, so the allowlist is mirrored here and kept current from the
     * database. A revoke lands in this set before the transaction that caused it is even
     * observed by the UI, which is the right ordering for a control whose whole job is
     * to take effect immediately.
     */
    @Volatile private var allowed: Set<IdentityKey> = emptySet()

    private val _pairing = MutableStateFlow<CameraPairingSession?>(null)
    val pairing: StateFlow<CameraPairingSession?> = _pairing.asStateFlow()

    /**
     * Set the moment a pairing handshake is accepted, so the camera screen can show the
     * phrase while the viewer is still holding its phone up (§2.3). Both ends derive it
     * from the same transcript; a relay in the middle cannot make them agree.
     */
    private val _pairingPhrase = MutableStateFlow<String?>(null)
    val pairingPhrase: StateFlow<String?> = _pairingPhrase.asStateFlow()

    fun acknowledgePhrase() { _pairingPhrase.value = null }

    val roster: StateFlow<List<ViewerEntity>> = viewers.observeAll()
        .stateIn(graph.scope, SharingStarted.Eagerly, emptyList())

    val accessCount: StateFlow<Int> = viewers.observeCount()
        .stateIn(graph.scope, SharingStarted.Eagerly, 0)

    init {
        graph.scope.launch {
            viewers.observeAll()
                .map { rows -> rows.map { IdentityKey(it.publicKey) }.toSet() }
                .collect { allowed = it }
        }
    }

    fun isAllowed(key: IdentityKey): Boolean = key in allowed

    fun activePairing(): CameraPairingSession? =
        _pairing.value?.takeIf { it.state(now()) == CameraPairingSession.State.ACTIVE }

    /**
     * Opens the 120-second window (§2.3). Returns null when the phone has no usable LAN
     * address, because a QR pointing nowhere is worse than a clear refusal.
     */
    fun startPairing(port: Int): CameraPairingSession? {
        val host = LocalAddress.best() ?: return null
        val session = CameraPairingSession(
            cameraIdentity = graph.identity.identity,
            host = host,
            port = port,
            cameraAlias = graph.settings.current.alias,
            startedAtMs = now(),
        )
        _pairing.value = session
        graph.scope.launch {
            graph.audit.record(AuditEvent.Kind.PAIRING_STARTED, detail = "$host:$port")
        }
        return session
    }

    fun cancelPairing() {
        val session = _pairing.value ?: return
        session.cancel()
        _pairing.value = null
        graph.scope.launch { graph.audit.record(AuditEvent.Kind.PAIRING_EXPIRED, detail = "cancelled") }
    }

    /** Called by the signaling server once a pairing handshake has been accepted. */
    suspend fun completePairing(
        viewer: IdentityKey,
        label: String,
        sourceIp: String,
        verificationPhrase: String,
    ) {
        viewers.upsert(
            ViewerEntity(
                fingerprint = viewer.shortFingerprint,
                publicKey = viewer.spki,
                label = label.ifBlank { "Unnamed device" },
                pairedAtMs = now(),
                lastSeenMs = now(),
            )
        )
        allowed = allowed + viewer
        _pairing.value = null
        _pairingPhrase.value = verificationPhrase
        graph.audit.record(
            AuditEvent.Kind.PAIRING_COMPLETED,
            viewerFingerprint = viewer.shortFingerprint,
            viewerLabel = label,
            sourceIp = sourceIp,
        )
    }

    suspend fun revoke(fingerprint: String) {
        val row = viewers.byFingerprint(fingerprint) ?: return
        allowed = allowed - IdentityKey(row.publicKey)
        viewers.delete(fingerprint)
        graph.audit.record(
            AuditEvent.Kind.VIEWER_REVOKED,
            viewerFingerprint = fingerprint,
            viewerLabel = row.label,
        )
        onRevoked?.invoke(fingerprint)
    }

    suspend fun revokeAll() {
        val rows = viewers.all()
        allowed = emptySet()
        viewers.deleteAll()
        rows.forEach {
            graph.audit.record(
                AuditEvent.Kind.VIEWER_REVOKED,
                viewerFingerprint = it.fingerprint,
                viewerLabel = it.label,
                detail = "revoke all",
            )
            onRevoked?.invoke(it.fingerprint)
        }
    }

    suspend fun sessionsIn30d(fingerprint: String): Int =
        graph.audit.sessionsIn(fingerprint, now() - THIRTY_DAYS_MS)

    /** Set by the signaling server so a revoke tears down a live session mid-frame (§2.6). */
    var onRevoked: ((String) -> Unit)? = null

    private fun now() = System.currentTimeMillis()

    private companion object { const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000 }
}
