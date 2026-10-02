package com.homecam.core.crypto

import java.net.URI

/**
 * What a push endpoint may be. The camera POSTs to `{endpoint}/{topic}` on behalf of a
 * viewer, so an unconstrained endpoint turns the camera into a request sender for whoever
 * can set it. Private and LAN addresses are allowed on purpose, because a self-hosted ntfy
 * lives on one; what is not allowed is cleartext, credentials in the URL, or anything that
 * is not a bare origin with an optional path prefix.
 */
object AlertEndpoint {
    const val MAX_LENGTH = 200

    sealed interface Result {
        data class Valid(val endpoint: String) : Result
        data class Refused(val reason: String) : Result
    }

    fun check(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return Result.Refused("length")
        if (text.any { it.isISOControl() || it.isWhitespace() }) return Result.Refused("characters")
        val uri = runCatching { URI(text) }.getOrNull() ?: return Result.Refused("not a URL")
        if (uri.scheme?.lowercase() != "https") return Result.Refused("not https")
        if (uri.rawUserInfo != null) return Result.Refused("credentials in URL")
        val host = uri.host ?: return Result.Refused("no host")
        if (host.isBlank()) return Result.Refused("no host")
        if (uri.rawQuery != null || uri.rawFragment != null) return Result.Refused("query or fragment")
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.split('/').any { it == ".." || it == "." }) return Result.Refused("path")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return Result.Valid("https://${host.lowercase()}$port$path")
    }

    fun validOrNull(raw: String): String? = (check(raw) as? Result.Valid)?.endpoint

    /** The host to show a person, so a surprising destination is visible. */
    fun hostOf(endpoint: String): String = runCatching { URI(endpoint).host }.getOrNull().orEmpty()
}
