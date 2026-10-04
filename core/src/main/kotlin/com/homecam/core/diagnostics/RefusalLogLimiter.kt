package com.homecam.core.diagnostics

/**
 * Stops a refused-connection flood from rotating the diagnostics log out from under the
 * lines that matter. Each peer class and reason may record [MAX_PER_WINDOW] lines per
 * [WINDOW_MS]; further refusals are counted, and the next line that key is allowed to
 * record carries the count once. The caller passes the clock so the decision and the
 * written line share one timestamp. [MAX_KEYS] is above the closed set of peer class ×
 * refusal reason, so a new key drops the quietest one — and that key's unreported count —
 * rather than growing the map.
 */
class RefusalLogLimiter(
    private val maxPerWindow: Int = MAX_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
    private val maxKeys: Int = MAX_KEYS,
) {
    init {
        require(maxPerWindow >= 1)
        require(windowMs > 0)
        require(maxKeys >= 1)
    }

    sealed interface Decision {
        data class Record(val suppressed: Int) : Decision
        data object Drop : Decision
    }

    private class Slot(var windowStartMs: Long, var recorded: Int, var suppressed: Int)

    private val slots = object : LinkedHashMap<String, Slot>(maxKeys, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot>?) = size > maxKeys
    }

    @Synchronized
    fun onRefusal(peer: PeerPath, reason: String?, nowMs: Long): Decision {
        val key = peer.wire + "\u0000" + reason.orEmpty()
        val slot = slots[key]
        if (slot == null) {
            slots[key] = Slot(nowMs, recorded = 1, suppressed = 0)
            return Decision.Record(0)
        }
        if (nowMs - slot.windowStartMs >= windowMs) {
            val carried = slot.suppressed
            slot.windowStartMs = nowMs
            slot.recorded = 1
            slot.suppressed = 0
            return Decision.Record(carried)
        }
        if (slot.recorded < maxPerWindow) {
            slot.recorded += 1
            return Decision.Record(0)
        }
        slot.suppressed += 1
        return Decision.Drop
    }

    /** Keys currently remembered. Never more than [maxKeys]. */
    @Synchronized
    fun trackedKeys(): Int = slots.size

    companion object {
        const val MAX_PER_WINDOW = 5
        const val WINDOW_MS = 60_000L
        const val MAX_KEYS = 128
    }
}
