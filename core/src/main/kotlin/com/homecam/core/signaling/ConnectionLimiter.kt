package com.homecam.core.signaling

/**
 * Bounds how many connections the signaling port will hold open, in total and per source
 * address, so a peer that can reach the port cannot park thousands of half-open sessions on
 * a phone that is also encoding video. A refused connection is closed before it can ask for
 * anything. Limits are generous for a household: a viewer that reconnects while its old
 * socket is still waiting out the lost-connection timeout holds two.
 */
class ConnectionLimiter(
    private val maxTotal: Int = MAX_TOTAL,
    private val maxPerAddress: Int = MAX_PER_ADDRESS,
) {
    private val perAddress = mutableMapOf<String, Int>()
    private var total = 0

    @Synchronized
    fun tryAdmit(address: String): Boolean {
        if (total >= maxTotal || (perAddress[address] ?: 0) >= maxPerAddress) return false
        total++
        perAddress[address] = (perAddress[address] ?: 0) + 1
        return true
    }

    @Synchronized
    fun release(address: String) {
        val held = perAddress[address] ?: return
        total--
        if (held <= 1) perAddress.remove(address) else perAddress[address] = held - 1
    }

    @Synchronized
    fun open(): Int = total

    companion object {
        const val MAX_TOTAL = 12
        const val MAX_PER_ADDRESS = 4
    }
}

/**
 * Turns a stream of identical refusals into one audit row per window, with the count of
 * the ones that were not written, so a flood neither grows the log without bound nor buries
 * the one rejection that matters. The first refusal in a window is always recorded at once.
 */
class RejectionCoalescer(private val windowMs: Long = WINDOW_MS) {

    sealed interface Decision {
        data object Record : Decision

        /** [startsWindow] is true for the first suppression, which is when the summary must be scheduled. */
        data class Suppress(val startsWindow: Boolean) : Decision
    }

    private class Window(var openedAtMs: Long, var suppressed: Int)

    private val windows = mutableMapOf<String, Window>()

    @Synchronized
    fun onReject(key: String, nowMs: Long): Decision {
        val window = windows[key]
        if (window == null || nowMs - window.openedAtMs >= windowMs) {
            windows[key] = Window(nowMs, 0)
            return Decision.Record
        }
        window.suppressed++
        return Decision.Suppress(startsWindow = window.suppressed == 1)
    }

    /** The number of refusals suppressed under [key], clearing it; zero when nothing was. */
    @Synchronized
    fun takeSuppressed(key: String): Int {
        val window = windows[key] ?: return 0
        val count = window.suppressed
        windows.remove(key)
        return count
    }

    val windowDurationMs: Long get() = windowMs

    companion object {
        const val WINDOW_MS = 60_000L
    }
}
