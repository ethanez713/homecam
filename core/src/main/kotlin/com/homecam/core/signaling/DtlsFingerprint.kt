package com.homecam.core.signaling

/**
 * §2.5, the step that is easy to skip and expensive to get wrong.
 *
 * WebRTC's media encryption is keyed by DTLS, and the fingerprint of the certificate that
 * keys it travels in the SDP. Authenticating the signaling channel therefore only proves
 * who you negotiated *with* — it says nothing about who ends up on the other end of the
 * media. An attacker who can inject SDP still gets the video, over a perfectly
 * authenticated WebSocket.
 *
 * So the fingerprint parsed out of a received SDP must equal the one the authenticated
 * peer committed to over the pinned channel. Mismatch means tear the session down.
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
 * it; every SDP after that must agree.
 *
 * The re-offer case is the one worth spelling out. Renegotiation happens for ordinary
 * reasons — an added audio track, an ICE restart — and each one carries a fresh SDP. If
 * only the first were checked, a session could be handed a new DTLS identity halfway
 * through and carry on streaming to somebody else. So the commitment outlives the offer
 * that created it.
 */
class DtlsCommitment {

    enum class Result { ACCEPTED, MISSING, WEAK_HASH, INCONSISTENT, CHANGED }

    var committed: String? = null
        private set

    /**
     * [declared] is what the peer said over the authenticated channel; [sdp] is what it
     * actually sent. They must agree with each other and with anything committed earlier.
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
