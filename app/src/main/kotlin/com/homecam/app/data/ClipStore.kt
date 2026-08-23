package com.homecam.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.homecam.app.media.ClipRecorder
import com.homecam.core.retention.ClipRef
import com.homecam.core.retention.PrunePlan
import com.homecam.core.retention.RetentionLimits
import com.homecam.core.retention.RetentionPlanner
import com.homecam.proto.Clip
import com.homecam.proto.RetentionPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Clips live in app-private storage — never `MediaStore`, never a shared directory — so
 * another app on the phone cannot read them (§2.8, T6). There is deliberately no second
 * encryption layer: Android's file-based encryption already covers device theft, and a
 * per-file key stored on the same phone would be security theatre.
 */
class ClipStore(context: Context, private val dao: ClipDao) {

    val directory: File = File(context.filesDir, "clips").apply { mkdirs() }

    private val pruneLock = Mutex()

    suspend fun index(written: ClipRecorder.Written, labels: List<String> = emptyList()): ClipEntity {
        val dimensions = readDimensions(written.file)
        val entity = ClipEntity(
            id = written.id,
            path = written.file.absolutePath,
            startedAtMs = written.startedAtMs,
            durationMs = written.durationMs,
            sizeBytes = written.file.length(),
            width = dimensions.first,
            height = dimensions.second,
            hasAudio = false,
            pinned = false,
            labels = labels.joinToString(","),
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)

    suspend fun delete(id: String): Boolean {
        val clip = dao.byId(id) ?: return false
        File(clip.path).delete()
        dao.delete(id)
        return true
    }

    suspend fun file(id: String): File? = dao.byId(id)?.let { File(it.path) }?.takeIf { it.exists() }

    /**
     * Retention is whichever of the three limits bites first, and pinned clips are exempt
     * from all of them (§5). Runs after every clip write as well as on a timer, because
     * the limit that matters most — free space — can be crossed by one long clip.
     */
    suspend fun prune(policy: RetentionPolicy): PrunePlan = pruneLock.withLock {
        val limits = RetentionLimits(
            maxAgeDays = policy.maxAgeDays.takeIf { it > 0 } ?: 14,
            maxSizeGb = policy.maxSizeGb.takeIf { it > 0 } ?: 32,
            minFreeSpaceGb = policy.minFreeSpaceGb.takeIf { it > 0 } ?: 8,
        )
        val clips = dao.all().map { it.toRef() }
        val plan = RetentionPlanner.plan(
            clips = clips,
            limits = limits,
            nowMs = System.currentTimeMillis(),
            deviceFreeBytes = directory.usableSpace,
        )
        plan.delete.forEach { delete(it.id) }
        removeOrphanFiles()
        plan
    }

    suspend fun projectedRetentionDays(policy: RetentionPolicy): Double? =
        RetentionPlanner.projectedRetentionDays(
            dao.all().map { it.toRef() },
            RetentionLimits(
                maxAgeDays = policy.maxAgeDays.takeIf { it > 0 } ?: 14,
                maxSizeGb = policy.maxSizeGb.takeIf { it > 0 } ?: 32,
                minFreeSpaceGb = policy.minFreeSpaceGb.takeIf { it > 0 } ?: 8,
            ),
            System.currentTimeMillis(),
        )

    /**
     * A file with no row is invisible to retention and would grow the library forever —
     * the normal way to get one is a clip whose muxer was killed mid-write.
     */
    private suspend fun removeOrphanFiles() {
        val known = dao.all().mapTo(HashSet()) { it.path }
        directory.listFiles()?.forEach { file ->
            if (file.absolutePath !in known) file.delete()
        }
    }

    // MediaMetadataRetriever only became AutoCloseable at API 29, so `use` would throw on
    // the Android 8 devices minSdk 26 exists to support.
    private fun readDimensions(file: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            (width?.toIntOrNull() ?: 0) to (height?.toIntOrNull() ?: 0)
        } catch (e: Exception) {
            Log.w(TAG, "could not read clip dimensions", e)
            0 to 0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object { const val TAG = "ClipStore" }
}

fun ClipEntity.toRef() = ClipRef(id, startedAtMs, sizeBytes, pinned)

fun ClipEntity.toProto(): Clip = Clip.newBuilder()
    .setId(id)
    .setStartedAtMs(startedAtMs)
    .setDurationMs(durationMs)
    .setSizeBytes(sizeBytes)
    .setPinned(pinned)
    .addAllLabels(labels.split(",").filter { it.isNotBlank() })
    .setWidth(width)
    .setHeight(height)
    .setHasAudio(hasAudio)
    .build()
