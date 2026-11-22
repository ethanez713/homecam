package com.homecam.app.camera

import android.system.ErrnoException
import android.system.Os
import com.homecam.core.unlock.UnlockPolicy
import com.homecam.core.unlock.UnlockState
import java.io.File

/**
 * The gesture code and the lockout count, which the snapshot does not carry.
 * The directory is 0700 and each file is 0600. Both modes are set again on
 * every write, because creating a file applies the process umask.
 */
class UnlockStore(filesDir: File) {

    private val dir = File(filesDir, DIR)
    private val codeFile = File(dir, "code")
    private val stateFile = File(dir, "state")
    private val gate = Any()

    data class Reading(val codeSet: Boolean, val state: UnlockState)

    fun read(): Reading = synchronized(gate) {
        Reading(codeSetLocked(), readStateLocked())
    }

    /** False when enabling was asked for and no code is stored. Nothing is written then. */
    fun accept(enabled: Boolean, clearLockout: Boolean): Boolean = synchronized(gate) {
        val state = readStateLocked()
        val next = UnlockPolicy.applySettings(enabled, clearLockout, codeSetLocked(), state) ?: return false
        if (clearLockout) writePrivate(stateFile, UnlockPolicy.encodeState(next))
        true
    }

    fun writeCode(signs: List<Int>) = synchronized(gate) {
        if (!UnlockPolicy.validCode(signs)) throw IllegalArgumentException("invalid code")
        writePrivate(codeFile, encode(signs))
        writePrivate(stateFile, UnlockPolicy.encodeState(UnlockPolicy.clear(readStateLocked())))
    }

    private fun codeSetLocked(): Boolean {
        if (!codeFile.isFile) return false
        val bytes = runCatching { codeFile.readBytes() }.getOrNull() ?: return false
        if (bytes.size != UnlockPolicy.CODE_LENGTH) return false
        val signs = ArrayList<Int>(bytes.size)
        for (byte in bytes) {
            val digit = byte.toInt() - '0'.code
            if (digit !in 1..5) return false
            signs += digit
        }
        return UnlockPolicy.validCode(signs)
    }

    private fun readStateLocked(): UnlockState {
        if (!stateFile.isFile) return UnlockState()
        val bytes = runCatching { stateFile.readBytes() }.getOrNull() ?: return UnlockState()
        return UnlockPolicy.decodeState(bytes)
    }

    private fun encode(signs: List<Int>): ByteArray {
        val bytes = ByteArray(signs.size)
        for (i in signs.indices) bytes[i] = ('0'.code + signs[i]).toByte()
        return bytes
    }

    private fun writePrivate(file: File, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) error("could not create directory")
        chmod(dir, DIR_MODE)
        file.writeBytes(bytes)
        chmod(file, FILE_MODE)
        if (codeFile.isFile) chmod(codeFile, FILE_MODE)
        if (stateFile.isFile) chmod(stateFile, FILE_MODE)
        chmod(dir, DIR_MODE)
    }

    private fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.path, mode)
        } catch (e: ErrnoException) {
            throw IllegalStateException("could not set file mode", e)
        }
    }

    private companion object {
        const val DIR = "unlock"
        const val DIR_MODE = 448 // 0700
        const val FILE_MODE = 384 // 0600
    }
}
