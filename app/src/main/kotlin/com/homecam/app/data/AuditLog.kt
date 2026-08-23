package com.homecam.app.data

import com.google.protobuf.ByteString
import com.homecam.core.audit.AuditChain
import com.homecam.core.crypto.Canonical
import com.homecam.proto.AuditEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The append-only access log (§2.7). Every write goes through [mutex]: two concurrent
 * appends would each read the same tail hash and produce a fork, which verification
 * would then report as tampering.
 */
class AuditLog(
    private val dao: AuditDao,
    /**
     * Where the tail marker lives. A hash chain proves nothing about entries that were
     * never there — deleting the *last* row leaves a chain that verifies perfectly, and
     * the last row is exactly what somebody who just got refused would delete. So the
     * head is also written outside the database, and a log shorter than its own marker is
     * truncation rather than a short history.
     *
     * Null disables the marker, which is only right in tests that construct a log with no
     * storage of their own.
     */
    private val headFile: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

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
        writeHead(linked.seq, linked.chainHash.toByteArray())
        linked
    }

    fun observeRecent(limit: Int = 200): Flow<List<AuditEvent>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toProto() } }

    suspend fun between(sinceMs: Long, untilMs: Long, limit: Int): List<AuditEvent> =
        dao.between(sinceMs, untilMs, limit).map { it.toProto() }

    suspend fun sessionsIn(fingerprint: String, sinceMs: Long): Int =
        dao.countOf(fingerprint, AuditEvent.Kind.SESSION_OPENED.number, sinceMs)

    /** Reads the whole log and re-derives every hash. Surfaced in the log UI (§2.7). */
    suspend fun verify(): AuditChain.Verification {
        val rows = dao.all()
        val head = readHead()
        if (head != null) {
            val last = rows.lastOrNull()
            if (last == null || last.seq < head.first) {
                return AuditChain.Verification(false, (last?.seq ?: -1L) + 1)
            }
            if (last.seq == head.first && !last.chainHash.contentEquals(head.second)) {
                return AuditChain.Verification(false, last.seq)
            }
        }
        return AuditChain.verify(rows.map { it.toProto() })
    }

    private fun writeHead(seq: Long, chainHash: ByteArray) {
        val file = headFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(Canonical.long(seq) + chainHash)
            file.setReadable(false, false)
            file.setReadable(true, true)
        }
    }

    private fun readHead(): Pair<Long, ByteArray>? {
        val file = headFile?.takeIf { it.exists() } ?: return null
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size == 8 + 32)
            var seq = 0L
            for (i in 0 until 8) seq = (seq shl 8) or (bytes[i].toLong() and 0xFF)
            seq to bytes.copyOfRange(8, bytes.size)
        }.getOrNull()
    }
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
