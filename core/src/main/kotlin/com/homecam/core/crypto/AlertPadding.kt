package com.homecam.core.crypto

import com.google.protobuf.ByteString
import com.homecam.proto.AlertPayload
import java.util.Base64

/**
 * The push provider sees the size of every sealed body. Padding the plaintext to one of a
 * few sizes means the size says "small, medium or large", not "this alert has a thumbnail
 * of 1,873 bytes".
 */
object AlertPadding {
    private val BUCKETS = intArrayOf(512, 1024, 2048)

    /** A payload padded to the smallest bucket that holds it, or unchanged when none fits under the body limit. */
    fun pad(payload: AlertPayload): AlertPayload {
        val bare = payload.toBuilder().clearPadding().build()
        val size = bare.serializedSize
        val target = BUCKETS.firstOrNull { it >= size + MIN_FIELD_OVERHEAD } ?: return bare
        if (sealedBodySize(target) > AlertLimits.MAX_SEALED_BODY_BYTES) return bare

        var fill = target - size - MIN_FIELD_OVERHEAD
        while (fill >= 0) {
            val candidate = bare.toBuilder().setPadding(ByteString.copyFrom(ByteArray(fill))).build()
            val actual = candidate.serializedSize
            if (actual == target) return candidate
            fill -= (actual - target)
        }
        return bare
    }

    /** The Base64 body length of an envelope around [plaintextBytes], per the AlertCrypto wire format. */
    fun sealedBodySize(plaintextBytes: Int): Int {
        val sealed = ENVELOPE_OVERHEAD + plaintextBytes
        return Base64.getEncoder().encode(ByteArray(sealed)).size
    }

    // version + ephemeral length + a P-256 SPKI + nonce + GCM tag.
    private const val ENVELOPE_OVERHEAD = 3 + 91 + 12 + 16
    private const val MIN_FIELD_OVERHEAD = 1 + 2
}
