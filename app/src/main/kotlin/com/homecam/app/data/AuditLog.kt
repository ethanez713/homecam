package com.homecam.app.data

import com.google.protobuf.ByteString
import com.homecam.core.audit.AuditChain
import com.homecam.proto.AuditEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The append-only access log (§2.7). Every write goes through [mutex]: two concurrent
 * appends would each read the same tail hash and produce a fork, which verification
 * would then report as tampering.
 */
class AuditLog(private val dao: AuditDao, private val clock: () -> Long = System::currentTimeMillis) {

    private val mutex = Mutex()

    suspend fun record(
        kind: AuditEvent.Kind,
        viewerFingerprint: String = "",
        viewerLabel: String = "",
        sourceIp: String = "",
        detail: String = "",
    ): AuditEvent = mutex.withLock {
        val tail = dao.last()
        val previous = tail?.chainHash ?: AuditChain.GENESIS
        val linked = AuditChain.link(
            previous,
            AuditEvent.newBuilder()
                .setSeq((tail?.seq ?: -1L) + 1)
                .setAtMs(clock())
                .setKind(kind)
                .setViewerFingerprint(viewerFingerprint)
                .setViewerLabel(viewerLabel)
                .setSourceIp(sourceIp)
                .setDetail(detail)
                .build(),
        )
        dao.insert(linked.toEntity())
        linked
    }

    fun observeRecent(limit: Int = 200): Flow<List<AuditEvent>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toProto() } }

    suspend fun between(sinceMs: Long, untilMs: Long, limit: Int): List<AuditEvent> =
        dao.between(sinceMs, untilMs, limit).map { it.toProto() }

    suspend fun sessionsIn(fingerprint: String, sinceMs: Long): Int =
        dao.countOf(fingerprint, AuditEvent.Kind.SESSION_OPENED.number, sinceMs)

    /** Reads the whole log and re-derives every hash. Surfaced in the log UI (§2.7). */
    suspend fun verify(): AuditChain.Verification = AuditChain.verify(dao.all().map { it.toProto() })
}

fun AuditEvent.toEntity() = AuditEntity(
    seq = seq,
    atMs = atMs,
    kind = kindValue,
    viewerFingerprint = viewerFingerprint,
    viewerLabel = viewerLabel,
    sourceIp = sourceIp,
    detail = detail,
    prevHash = prevHash.toByteArray(),
    chainHash = chainHash.toByteArray(),
)

fun AuditEntity.toProto(): AuditEvent = AuditEvent.newBuilder()
    .setSeq(seq)
    .setAtMs(atMs)
    .setKindValue(kind)
    .setViewerFingerprint(viewerFingerprint)
    .setViewerLabel(viewerLabel)
    .setSourceIp(sourceIp)
    .setDetail(detail)
    .setPrevHash(ByteString.copyFrom(prevHash))
    .setChainHash(ByteString.copyFrom(chainHash))
    .build()
