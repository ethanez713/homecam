package com.homecam.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.data.HomecamDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A destructive migration would drop the allowlist, so each step is proven against a real
 * database of the previous version, built from that version's schema.
 */
@RunWith(AndroidJUnit4::class)
class ViewerMigrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After
    fun clean() {
        context.deleteDatabase(name)
    }

    @Test
    fun aVersion3ViewerKeepsItsRowAndGainsHealthAlertsOn() = runBlocking {
        context.deleteDatabase(name)
        val file: File = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            V3_SCHEMA.forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO viewers VALUES ('AAAA-BBBB', x'0102', 'Old phone', 1, 2, NULL, '', '', 0, 1, 1)",
            )
            db.version = 3
        }

        val database = Room.databaseBuilder(context, HomecamDatabase::class.java, name)
            .addMigrations(*HomecamDatabase.MIGRATIONS)
            .build()
        try {
            val viewer = database.viewers().byFingerprint("AAAA-BBBB")
            assertEquals("Old phone", viewer?.label)
            assertTrue("a migrated viewer must receive health alerts", viewer?.healthAlerts == true)
        } finally {
            database.close()
        }
    }

    private companion object {
        val V3_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `viewers` (`fingerprint` TEXT NOT NULL, `publicKey` BLOB NOT NULL, `label` TEXT NOT NULL, `pairedAtMs` INTEGER NOT NULL, `lastSeenMs` INTEGER NOT NULL, `alertPublicKey` BLOB, `alertTopic` TEXT NOT NULL, `alertEndpoint` TEXT NOT NULL, `alertsEnabled` INTEGER NOT NULL, `sessionAlerts` INTEGER NOT NULL, `detectionAlerts` INTEGER NOT NULL, PRIMARY KEY(`fingerprint`))",
            "CREATE TABLE IF NOT EXISTS `cameras` (`fingerprint` TEXT NOT NULL, `publicKey` BLOB NOT NULL, `alias` TEXT NOT NULL, `lastHost` TEXT NOT NULL, `lastPort` INTEGER NOT NULL, `remoteHost` TEXT NOT NULL, `remotePort` INTEGER NOT NULL, `pairedAtMs` INTEGER NOT NULL, `lastSeenMs` INTEGER NOT NULL, `lastArmed` INTEGER NOT NULL, `lastRecordingMode` TEXT NOT NULL, PRIMARY KEY(`fingerprint`))",
            "CREATE TABLE IF NOT EXISTS `audit` (`seq` INTEGER NOT NULL, `atMs` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `viewerFingerprint` TEXT NOT NULL, `viewerLabel` TEXT NOT NULL, `sourceIp` TEXT NOT NULL, `detail` TEXT NOT NULL, `prevHash` BLOB NOT NULL, `chainHash` BLOB NOT NULL, PRIMARY KEY(`seq`))",
            "CREATE INDEX IF NOT EXISTS `index_audit_atMs` ON `audit` (`atMs`)",
            "CREATE INDEX IF NOT EXISTS `index_audit_kind` ON `audit` (`kind`)",
            "CREATE TABLE IF NOT EXISTS `clips` (`id` TEXT NOT NULL, `path` TEXT NOT NULL, `startedAtMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `hasAudio` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `labels` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_clips_startedAtMs` ON `clips` (`startedAtMs`)",
            "CREATE INDEX IF NOT EXISTS `index_clips_pinned` ON `clips` (`pinned`)",
        )
    }
}
