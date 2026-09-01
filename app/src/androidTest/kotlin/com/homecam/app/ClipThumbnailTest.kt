package com.homecam.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.data.ClipEntity
import com.homecam.app.data.ClipStore
import com.homecam.app.data.HomecamDatabase
import com.homecam.proto.RetentionPolicy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Phase 1.4: a clip's thumbnail is stored beside it, served by the THUMBNAIL handler, and
 * pruned exactly when the clip is — never orphan-collected out from under a live clip. The
 * frame extraction itself is Android glue exercised by recording on a device; what regresses
 * quietly is the delete/retention bookkeeping, which is what these pin.
 */
@RunWith(AndroidJUnit4::class)
class ClipThumbnailTest {

    private lateinit var database: HomecamDatabase
    private lateinit var store: ClipStore
    private val created = mutableListOf<File>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, HomecamDatabase::class.java).build()
        store = ClipStore(context, database.clips())
    }

    @After
    fun tearDown() {
        created.forEach { it.delete() }
        database.close()
    }

    private fun jpegBytes(): ByteArray = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }

    private suspend fun seedClip(id: String): ClipEntity {
        val file = File(store.directory, "$id.mp4").apply { writeBytes(ByteArray(16)) }
        val thumb = store.thumbnailFile(file).apply { writeBytes(jpegBytes()) }
        created += file
        created += thumb
        val entity = ClipEntity(
            id = id,
            path = file.absolutePath,
            startedAtMs = System.currentTimeMillis(),
            durationMs = 1_000,
            sizeBytes = file.length(),
            width = 320,
            height = 240,
            hasAudio = false,
            pinned = false,
            labels = "",
        )
        database.clips().upsert(entity)
        return entity
    }

    @Test
    fun theThumbnailIsServedAsADecodableJpeg() = runTest {
        seedClip("clip-a")
        val file = store.thumbnail("clip-a")
        assertNotNull("no thumbnail returned", file)
        val bitmap = BitmapFactory.decodeByteArray(file!!.readBytes(), 0, file.length().toInt())
        assertNotNull("thumbnail did not decode", bitmap)
    }

    @Test
    fun deletingAClipRemovesBothFiles() = runTest {
        val clip = seedClip("clip-b")
        val clipFile = File(clip.path)
        val thumbFile = store.thumbnailFile(clipFile)
        assertTrue(clipFile.exists() && thumbFile.exists())

        assertTrue(store.delete("clip-b"))
        assertFalse("clip file survived delete", clipFile.exists())
        assertFalse("thumbnail survived delete", thumbFile.exists())
    }

    @Test
    fun pruneKeepsThumbnailsAndRemovesStrays() = runTest {
        val clip = seedClip("clip-c")
        val stray = File(store.directory, "stray.tmp").apply { writeBytes(ByteArray(8)) }
        created += stray

        store.prune(RetentionPolicy.getDefaultInstance())

        assertTrue("live clip's thumbnail was collected", store.thumbnailFile(File(clip.path)).exists())
        assertFalse("stray file was not collected", stray.exists())
    }
}
