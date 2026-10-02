package com.homecam.app.data

import android.content.Context
import android.graphics.Bitmap
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
import java.io.FileOutputStream

/**
 * Clips live in app-private storage — never `MediaStore`, never a shared directory — so
 * another app on the phone cannot read them (§2.8, T6). There is deliberately no second
 * encryption layer: Android's file-based encryption already covers device theft, and a
 * per-file key stored on the same phone would be security theatre.
 */
class ClipStore(
    context: Context,
    private val dao: ClipDao,
    val directory: File = File(context.filesDir, "clips"),
) {

    init {
        directory.mkdirs()
    }

    private val pruneLock = Mutex()

    suspend fun index(written: ClipRecorder.Written, labels: List<String> = emptyList()): ClipEntity {
        val dimensions = readDimensions(written.file)
        // Generated once, now, so a viewer scrolling a list never makes the camera decode
        // video on demand. Stored beside the clip and pruned with it (§5).
        writeThumbnail(written.file)
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
        val file = File(clip.path)
        file.delete()
        thumbnailFile(file).delete()
        dao.delete(id)
        return true
    }

    suspend fun file(id: String): File? = dao.byId(id)?.let { File(it.path) }?.takeIf { it.exists() }

    suspend fun thumbnail(id: String): File? =
        dao.byId(id)?.let { thumbnailFile(File(it.path)) }?.takeIf { it.exists() }

    /** The clip's thumbnail sits beside it, so its path is derivable and needs no column. */
    fun thumbnailFile(clipFile: File): File = File(clipFile.absolutePath + ".jpg")

    private fun writeThumbnail(clipFile: File): File? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(clipFile.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scaled = scaleToWidth(frame, THUMBNAIL_WIDTH)
            val out = thumbnailFile(clipFile)
            FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, it) }
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
            out
        } catch (e: Exception) {
            Log.w(TAG, "could not write clip thumbnail", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleToWidth(bitmap: Bitmap, width: Int): Bitmap {
        if (bitmap.width <= width) return bitmap
        val height = (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * Retention is whichever of the three limits bites first, and pinned clips are exempt
     * from all of them (§5). Runs after every clip write as well as on a timer, because
     * the limit that matters most — free space — can be crossed by one long clip.
     */
    suspend fun prune(policy: RetentionPolicy): PrunePlan = pruneLock.withLock {
        val limits = policy.toRetentionLimits()
        val clips = dao.all().map { it.toRef() }
        val plan = RetentionPlanner.plan(
            clips = clips,
            limits = limits,
            nowMs = System.currentTimeMillis(),
            deviceFreeBytes = directory.usableSpace,
        )
        plan.delete.forEach { delete(it.id) }
        removeOrphanFiles()
        removeRowsWithoutFiles()
        lastPlan = plan
        plan
    }

    suspend fun projectedRetentionDays(policy: RetentionPolicy): Double? =
        RetentionPlanner.projectedRetentionDays(
            dao.all().map { it.toRef() },
            policy.toRetentionLimits(),
            System.currentTimeMillis(),
        )

    /**
     * A file with no row is invisible to retention and would grow the library forever —
     * the normal way to get one is a clip whose muxer was killed mid-write. The clip being
     * written right now has no row either, and deleting it leaves the recorder writing two
     * minutes into an unlinked file; its modification time is what tells the two apart.
     */
    private suspend fun removeOrphanFiles() {
        val known = HashSet<String>()
        dao.all().forEach { known += it.path; known += thumbnailFile(File(it.path)).absolutePath }
        val abandonedBefore = System.currentTimeMillis() - ORPHAN_GRACE_MS
        directory.listFiles()?.forEach { file ->
            if (file.absolutePath !in known && file.lastModified() < abandonedBefore) file.delete()
        }
    }

    /**
     * The mirror of [removeOrphanFiles]: a row whose file is gone or empty shows in the
     * viewer's list as a clip that cannot play, and counts against the size ceiling for
     * bytes that do not exist. Index runs only once a clip is closed, so a row never
     * belongs to a file still being written.
     */
    private suspend fun removeRowsWithoutFiles() {
        dao.all().filter { !File(it.path).let { file -> file.exists() && file.length() > 0 } }.forEach {
            Log.w(TAG, "dropping clip row ${it.id}: its file is missing or empty")
            thumbnailFile(File(it.path)).delete()
            dao.delete(it.id)
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

    companion object {
        /** The most recent prune in this process, whichever store ran it; health reads it. */
        @Volatile var lastPlan: PrunePlan? = null
            private set

        private const val TAG = "ClipStore"
        private const val THUMBNAIL_WIDTH = 320
        private const val THUMBNAIL_QUALITY = 75
        // Longer than any clip stays open: a segment is two minutes, a manual clip five.
        private const val ORPHAN_GRACE_MS = 15 * 60_000L
    }
}

fun ClipEntity.toRef() = ClipRef(id, startedAtMs, sizeBytes, pinned)

/** The wire policy's defaults, applied once — mirrors what [RetentionPlanner] is given. */
fun RetentionPolicy.toRetentionLimits() = RetentionLimits(
    maxAgeDays = maxAgeDays.takeIf { it > 0 } ?: 14,
    maxSizeGb = maxSizeGb.takeIf { it > 0 } ?: 32,
    minFreeSpaceGb = minFreeSpaceGb.takeIf { it > 0 } ?: 8,
)

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
