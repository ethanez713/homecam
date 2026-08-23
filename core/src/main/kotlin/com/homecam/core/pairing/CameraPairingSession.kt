package com.homecam.core.pairing

import com.google.protobuf.ByteString
import com.homecam.core.crypto.IdentityKey
import com.homecam.proto.PairingInvite
import java.security.SecureRandom
import java.util.Base64

/**
 * One pairing window on the camera. Deliberately narrow (§2.3): it must be started by
 * hand on an unlocked camera, it lives for [ttlMs], and one success ends it.
 */
class CameraPairingSession(
    private val cameraIdentity: IdentityKey,
    private val host: String,
    private val port: Int,
    private val cameraAlias: String,
    val startedAtMs: Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    random: SecureRandom = SecureRandom(),
) {
    val token: ByteArray = ByteArray(TOKEN_BYTES).also(random::nextBytes)
    val expiresAtMs: Long = startedAtMs + ttlMs

    private var consumed = false
    private var cancelled = false

    enum class State { ACTIVE, EXPIRED, CONSUMED, CANCELLED }

    fun state(nowMs: Long): State = when {
        cancelled -> State.CANCELLED
        consumed -> State.CONSUMED
        nowMs >= expiresAtMs -> State.EXPIRED
        else -> State.ACTIVE
    }

    fun remainingMs(nowMs: Long): Long = (expiresAtMs - nowMs).coerceAtLeast(0)

    fun cancel() { cancelled = true }

    /** Burns the window. One success ends pairing mode (§2.3). */
    fun consume(nowMs: Long): Boolean {
        if (state(nowMs) != State.ACTIVE) return false
        consumed = true
        return true
    }

    fun invite(): PairingInvite = PairingInvite.newBuilder()
        .setVersion(INVITE_VERSION)
        .setCameraPublicKey(ByteString.copyFrom(cameraIdentity.spki))
        .setHost(host)
        .setPort(port)
        .setToken(ByteString.copyFrom(token))
        .setExpiresAtMs(expiresAtMs)
        .setCameraAlias(cameraAlias)
        .build()

    /** What goes in the QR. */
    fun inviteText(): String = encodeInvite(invite())

    companion object {
        const val DEFAULT_TTL_MS = 120_000L
        const val TOKEN_BYTES = 32
        const val INVITE_VERSION = 1
        const val URI_SCHEME = "homecam:"

        fun encodeInvite(invite: PairingInvite): String =
            URI_SCHEME + Base64.getUrlEncoder().withoutPadding().encodeToString(invite.toByteArray())

        fun decodeInvite(text: String): PairingInvite? {
            if (!text.startsWith(URI_SCHEME)) return null
            return try {
                PairingInvite.parseFrom(Base64.getUrlDecoder().decode(text.removePrefix(URI_SCHEME)))
                    .takeIf { it.version == INVITE_VERSION && it.token.size() == TOKEN_BYTES }
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                null
            }
        }
    }
}
