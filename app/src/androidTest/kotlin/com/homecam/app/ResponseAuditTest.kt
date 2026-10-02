package com.homecam.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.data.AuditLog
import com.homecam.app.data.HomecamDatabase
import com.homecam.proto.AuditEvent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 8.4: a response action is an audited event, and recording it leaves the chain
 * still verifying — the property the whole audit design rests on.
 */
@RunWith(AndroidJUnit4::class)
class ResponseAuditTest {

    private lateinit var database: HomecamDatabase
    private lateinit var log: AuditLog
    private lateinit var headFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, HomecamDatabase::class.java).build()
        headFile = File(context.cacheDir, "audit-head-response-${System.nanoTime()}.bin")
        log = AuditLog(database.audit(), headFile)
    }

    @After
    fun tearDown() {
        database.close()
        headFile.delete()
    }

    @Test
    fun aSirenActivationIsLoggedAndTheChainStillVerifies() = runTest {
        log.record(AuditEvent.Kind.CAMERA_STARTED, detail = "boot")
        log.record(AuditEvent.Kind.RESPONSE_ACTION, detail = "siren")

        val events = database.audit().all()
        assertTrue(
            "the siren activation is in the log",
            events.any { it.kind == AuditEvent.Kind.RESPONSE_ACTION.number && it.detail == "siren" },
        )
        assertTrue("the chain still verifies", log.verify().valid)
    }

    @Test
    fun aResponseActionNamesTheViewerThatCausedIt() = runTest {
        log.record(
            AuditEvent.Kind.RESPONSE_ACTION,
            viewerFingerprint = "Y1TM-4JVY-9K8F", viewerLabel = "Ethan's S25", sourceIp = "100.64.0.21",
            detail = "siren",
        )

        val row = database.audit().all().single { it.detail == "siren" }
        assertTrue(row.viewerFingerprint == "Y1TM-4JVY-9K8F" && row.viewerLabel == "Ethan's S25")
        assertTrue(row.sourceIp == "100.64.0.21")
        assertTrue("the chain still verifies", log.verify().valid)
    }
}
