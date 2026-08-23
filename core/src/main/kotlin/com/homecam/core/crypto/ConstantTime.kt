package com.homecam.core.crypto

object ConstantTime {
    /** Comparison whose duration does not depend on where the first difference is. */
    fun equals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
