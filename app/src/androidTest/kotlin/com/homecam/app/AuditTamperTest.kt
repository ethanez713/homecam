package com.homecam.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.data.AuditLog
import com.homecam.app.data.HomecamDatabase
import com.homecam.proto.AuditEvent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M9's stated verification: tamper a log row, and the chain must notice.
 *
 * This runs against a real Room database rather than the pure-JVM chain test, because the
 * property that matters is about the *stored* log — somebody with the database file, not
 * somebody with the algorithm.
 */
@RunWith(AndroidJUnit4::class)
class AuditTamperTest {

    private lateinit var database: HomecamDatabase
    private lateinit var log: AuditLog
    private lateinit var headFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, HomecamDatabase::class.java).build()
        headFile = File(context.cacheDir, "audit-head-test-${System.nanoTime()}.bin")
        log = AuditLog(database.audit(), headFile)
    }

    @After
    fun tearDown() {
        database.close()
        headFile.delete()
    }

    private suspend fun writeSomeHistory() {
        log.record(AuditEvent.Kind.CAMERA_STARTED, detail = "port 8443")
        log.record(
            AuditEvent.Kind.SESSION_OPENED,
            viewerFingerprint = "7K2P-QM4X-9DFA",
            viewerLabel = "Ethan's S25",
            sourceIp = "192.168.1.42",
        )
        log.record(AuditEvent.Kind.SESSION_CLOSED, viewerFingerprint = "7K2P-QM4X-9DFA")
        log.record(AuditEvent.Kind.CONNECTION_REJECTED, sourceIp = "192.168.1.99", detail = "ACCESS_DENIED")
    }

    @Test
    fun anUntouchedLogVerifies() = runTest {
        writeSomeHistory()
        val result = log.verify()
        assertTrue("expected a valid chain, broke at ${result.firstBrokenSeq}", result.valid)
    }

    @Test
    fun editingARowIsDetected() = runTest {
        writeSomeHistory()
        // The interesting tamper: hide who connected, not that somebody did.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE audit SET viewerLabel = 'Someone else' WHERE seq = 1"
        )

        val result = log.verify()
        assertFalse(result.valid)
        assertEquals(1L, result.firstBrokenSeq)
    }

    @Test
    fun deletingARowFromTheMiddleIsDetected() = runTest {
        writeSomeHistory()
        database.openHelper.writableDatabase.execSQL("DELETE FROM audit WHERE seq = 1")

        val result = log.verify()
        assertFalse(result.valid)
        assertEquals(2L, result.firstBrokenSeq)
    }

    @Test
    fun deletingTheLastRowIsDetected() = runTest {
        writeSomeHistory()
        // The chain alone cannot see this — a truncated chain verifies perfectly — and it
        // is the entry somebody who had just been refused would delete. The tail marker
        // held outside the database is what catches it.
        database.openHelper.writableDatabase.execSQL("DELETE FROM audit WHERE seq = 3")

        val result = log.verify()
        assertFalse("truncation must not verify", result.valid)
        assertEquals(3L, result.firstBrokenSeq)
    }

    @Test
    fun emptyingTheWholeTableIsDetected() = runTest {
        writeSomeHistory()
        database.openHelper.writableDatabase.execSQL("DELETE FROM audit")
        assertFalse(log.verify().valid)
    }

    @Test
    fun sourceIpCannotBeQuietlyRewritten() = runTest {
        writeSomeHistory()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE audit SET sourceIp = '192.168.1.42' WHERE seq = 3"
        )
        assertFalse(log.verify().valid)
    }

    @Test
    fun aLogThatIsAppendedToNormallyKeepsVerifying() = runTest {
        writeSomeHistory()
        assertTrue(log.verify().valid)
        log.record(AuditEvent.Kind.VIEWER_REVOKED, viewerFingerprint = "7K2P-QM4X-9DFA")
        assertTrue(log.verify().valid)
    }

    @Test
    fun sessionsAreCountedForTheRoster() = runTest {
        writeSomeHistory()
        assertEquals(1, log.sessionsIn(fingerprint = "7K2P-QM4X-9DFA", sinceMs = 0))
        assertEquals(0, log.sessionsIn(fingerprint = "NOPE-NOPE-NOPE", sinceMs = 0))
    }
}
