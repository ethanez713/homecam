package com.homecam.core.retention

/** What the planner needs to know about a clip; the Room entity maps onto this. */
data class ClipRef(
    val id: String,
    val startedAtMs: Long,
    val sizeBytes: Long,
    val pinned: Boolean,
)

data class RetentionLimits(
    val maxAgeDays: Int = 14,
    val maxSizeGb: Int = 32,
    val minFreeSpaceGb: Int = 8,
) {
    val maxSizeBytes: Long get() = maxSizeGb.toLong() * GB
    val minFreeBytes: Long get() = minFreeSpaceGb.toLong() * GB
    val maxAgeMs: Long get() = maxAgeDays.toLong() * 24 * 60 * 60 * 1000

    companion object { const val GB = 1024L * 1024 * 1024 }
}

data class PrunePlan(
    val delete: List<ClipRef>,
    val pinnedBytes: Long,
    val remainingBytes: Long,
    /** True when pinned clips alone are within 10% of the size ceiling (§5). */
    val pinnedNearCeiling: Boolean,
)

/**
 * Retention is whichever of the three limits bites first (§5). **Pinned clips are exempt
 * from all of them** — without that exemption the one clip that mattered is exactly the
 * one that ages out.
 */
object RetentionPlanner {

    fun plan(
        clips: List<ClipRef>,
        limits: RetentionLimits,
        nowMs: Long,
        deviceFreeBytes: Long,
    ): PrunePlan {
        val pinnedBytes = clips.filter { it.pinned }.sumOf { it.sizeBytes }
        val candidates = clips.filterNot { it.pinned }.sortedBy { it.startedAtMs }.toMutableList()

        val doomed = LinkedHashSet<ClipRef>()
        var total = clips.sumOf { it.sizeBytes }
        var free = deviceFreeBytes

        fun evict(clip: ClipRef) {
            doomed += clip
            total -= clip.sizeBytes
            free += clip.sizeBytes
        }

        val oldestKept = nowMs - limits.maxAgeMs
        candidates.filter { it.startedAtMs < oldestKept }.forEach(::evict)
        candidates.removeAll(doomed)

        val byAge = candidates.iterator()
        while (byAge.hasNext() && (total > limits.maxSizeBytes || free < limits.minFreeBytes)) {
            evict(byAge.next())
            byAge.remove()
        }

        return PrunePlan(
            delete = doomed.toList(),
            pinnedBytes = pinnedBytes,
            remainingBytes = total,
            pinnedNearCeiling = pinnedNearCeiling(clips, limits),
        )
    }

    /** True when pinned clips alone are within 10% of the size ceiling (§5) — the point at
     * which pinning one more thing starts pushing history out. */
    fun pinnedNearCeiling(clips: List<ClipRef>, limits: RetentionLimits): Boolean =
        clips.filter { it.pinned }.sumOf { it.sizeBytes } > limits.maxSizeBytes * 9 / 10

    /**
     * "≈ 9 days at current rate", which is the number worth showing — far more useful
     * than the raw limits. Null when there is not enough history to say.
     */
    fun projectedRetentionDays(
        clips: List<ClipRef>,
        limits: RetentionLimits,
        nowMs: Long,
    ): Double? {
        val unpinned = clips.filterNot { it.pinned }
        if (unpinned.size < 2) return null
        val oldest = unpinned.minOf { it.startedAtMs }
        val spanDays = (nowMs - oldest).toDouble() / (24 * 60 * 60 * 1000)
        if (spanDays <= 0.0) return null
        val bytesPerDay = unpinned.sumOf { it.sizeBytes }.toDouble() / spanDays
        if (bytesPerDay <= 0.0) return null
        val budget = (limits.maxSizeBytes - clips.filter { it.pinned }.sumOf { it.sizeBytes })
            .coerceAtLeast(0).toDouble()
        return minOf(budget / bytesPerDay, limits.maxAgeDays.toDouble())
    }
}
