package com.homecam.core.crypto

/**
 * Crockford's base32 alphabet: no `I`, `L`, `O` or `U`, so a fingerprint read off one
 * screen and compared with another cannot be mis-copied into a different valid code.
 */
object CrockfordBase32 {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(bytes: ByteArray, chars: Int): String {
        val out = StringBuilder(chars)
        var buffer = 0L
        var bits = 0
        var index = 0
        while (out.length < chars) {
            if (bits < 5) {
                require(index < bytes.size) { "need ${chars * 5} bits, have ${bytes.size * 8}" }
                buffer = (buffer shl 8) or (bytes[index++].toLong() and 0xFF)
                bits += 8
            }
            bits -= 5
            out.append(ALPHABET[((buffer ushr bits) and 0x1FL).toInt()])
        }
        return out.toString()
    }

    fun groups(bytes: ByteArray, chars: Int, groupSize: Int): String =
        encode(bytes, chars).chunked(groupSize).joinToString("-")
}
