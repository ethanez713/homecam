package com.homecam.core.signaling

/**
 * §2.5: WebRTC's media encryption is keyed by DTLS, and the fingerprint of the certificate
 * that keys it travels in the SDP. An SDP is accepted only from a peer that has completed
 * the mutual handshake over the pinned channel, so the fingerprint it carries is the
 * authenticated peer's; a session whose SDP carries no usable fingerprint, a weak hash, or
 * a different fingerprint from an earlier SDP is torn down (D16).
 */
object DtlsFingerprint {

    private val LINE = Regex("""^a=fingerprint:(\S+)\s+(\S+)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Returns the single fingerprint an SDP commits to, or null when there is none — or
     * when there is **more than one distinct** fingerprint. A multi-fingerprint SDP is not
     * something a peer of ours ever sends, and treating it as ambiguous is safer than
     * picking one and hoping the media uses that one.
     */
    fun parse(sdp: String): String? {
        val found = sdp.lineSequence()
            .mapNotNull { LINE.find(it.trim()) }
            .map { "${it.groupValues[1].lowercase()} ${it.groupValues[2].uppercase()}" }
            .distinct()
            .take(2)
            .toList()
        return found.singleOrNull()
    }

    /**
     * Weak fingerprint hashes are refused outright: a peer that offers `sha-1` is either
     * ancient or trying to find a collision, and neither is a session worth having.
     */
    fun isAcceptable(fingerprint: String): Boolean =
        fingerprint.substringBefore(' ') in ACCEPTED_HASHES

    fun matches(committed: String, sdp: String): Boolean {
        val parsed = parse(sdp) ?: return false
        if (!isAcceptable(parsed)) return false
        return normalise(parsed) == normalise(committed)
    }

    private fun normalise(fingerprint: String): String {
        val hash = fingerprint.substringBefore(' ').trim().lowercase()
        val digits = fingerprint.substringAfter(' ').trim().uppercase()
        return "$hash $digits"
    }

    private val ACCEPTED_HASHES = setOf("sha-256", "sha-384", "sha-512")
}

/**
 * Holds the DTLS identity a session is pinned to. The first SDP a peer sends establishes
 * it; every SDP after that must agree, because renegotiation (an added track, an ICE
 * restart) carries a fresh SDP and a session must not be handed a new DTLS identity halfway
 * through.
 */
class DtlsCommitment {

    enum class Result { ACCEPTED, MISSING, WEAK_HASH, INCONSISTENT, CHANGED }

    var committed: String? = null
        private set

    /**
     * [declared] is the fingerprint the same message names; it must agree with [sdp] and with
     * anything committed earlier. Blank means the peer did not declare one.
     */
    fun check(sdp: String, declared: String): Result {
        val parsed = DtlsFingerprint.parse(sdp) ?: return Result.MISSING
        if (!DtlsFingerprint.isAcceptable(parsed)) return Result.WEAK_HASH
        if (declared.isNotBlank() && !DtlsFingerprint.matches(declared, sdp)) return Result.INCONSISTENT

        val previous = committed
        if (previous != null && !DtlsFingerprint.matches(previous, sdp)) return Result.CHANGED

        committed = parsed
        return Result.ACCEPTED
    }
}
