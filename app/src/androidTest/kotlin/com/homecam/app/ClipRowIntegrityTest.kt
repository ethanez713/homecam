package com.homecam.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.data.ClipEntity
import com.homecam.app.data.ClipStore
import com.homecam.app.data.HomecamDatabase
import com.homecam.proto.RetentionPolicy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A scratch directory and an in-memory database: the default clips directory is the real
 * library, and the orphan sweep that runs inside prune deletes anything in it the database
 * does not know.
 */
@RunWith(AndroidJUnit4::class)
class ClipRowIntegrityTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var database: HomecamDatabase

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "clip-row-integrity").apply { deleteRecursively(); mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, HomecamDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
        dir.deleteRecursively()
    }

    private fun row(id: String, file: File, size: Long = file.length(), pinned: Boolean = false) = ClipEntity(
        id = id, path = file.absolutePath, startedAtMs = System.currentTimeMillis(), durationMs = 1_000,
        sizeBytes = size, width = 0, height = 0, hasAudio = false, pinned = pinned, labels = "",
    )

    @Test
    fun rowsWhoseFileIsMissingOrEmptyAreDroppedAndTheRestKept() = runTest {
        val kept = File(dir, "kept.mp4").apply { writeBytes(ByteArray(64)) }
        val empty = File(dir, "empty.mp4").apply { writeBytes(ByteArray(0)) }
        val gone = File(dir, "gone.mp4")
        database.clips().upsert(row("kept", kept))
        database.clips().upsert(row("empty", empty, size = 0))
        database.clips().upsert(row("gone", gone, size = 4_000_000))
        database.clips().upsert(row("pinned-gone", File(dir, "pinned-gone.mp4"), size = 1, pinned = true))

        ClipStore(context, database.clips(), dir).prune(RetentionPolicy.getDefaultInstance())

        assertEquals(listOf("kept"), database.clips().all().map { it.id })
        assertEquals(true, kept.exists())
    }
}
