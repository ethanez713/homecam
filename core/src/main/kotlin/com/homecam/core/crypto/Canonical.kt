package com.homecam.core.crypto

import java.io.ByteArrayOutputStream

/**
 * Length-prefixed field concatenation. Prefixing is what makes the encoding injective:
 * without it `("ab","c")` and `("a","bc")` hash identically, and a signature over one
 * would be a valid signature over the other.
 */
object Canonical {

    fun encode(fields: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in fields) {
            out.write(field.size ushr 24); out.write(field.size ushr 16)
            out.write(field.size ushr 8); out.write(field.size)
            out.write(field)
        }
        return out.toByteArray()
    }

    fun encode(vararg fields: ByteArray): ByteArray = encode(fields.toList())

    fun long(value: Long) = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }
    fun int(value: Int) = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }
}
