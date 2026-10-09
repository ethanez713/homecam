package com.homecam.app

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homecam.app.camera.LensControl
import com.homecam.app.camera.LensSource
import com.homecam.app.data.AuditLog
import com.homecam.app.data.HomecamDatabase
import com.homecam.app.data.SettingsStore
import com.homecam.core.lens.LensSelections
import com.homecam.core.rpc.RpcException
import com.homecam.proto.AuditEvent
import com.homecam.proto.Lens
import com.homecam.proto.LensSelection
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Settings and audit live in throwaway files: the camera under test keeps its own. */
@RunWith(AndroidJUnit4::class)
class LensSettingTest {

    private val real get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var sandbox: File
    private lateinit var context: Context
    private lateinit var database: HomecamDatabase
    private lateinit var audit: AuditLog

    @Before
    fun setUp() {
        sandbox = File(real.cacheDir, "lens-setting-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(real) {
            override fun getFilesDir(): File = sandbox
        }
        database = Room.inMemoryDatabaseBuilder(real, HomecamDatabase::class.java).build()
        audit = AuditLog(database.audit(), File(sandbox, "audit-head.bin"))
    }

    @After
    fun tearDown() {
        database.close()
        sandbox.deleteRecursively()
    }

    private fun control(store: SettingsStore) = LensControl(store, audit) { LensSource.lenses(real).map { it.id } }

    @Test
    fun aFreshStoreIsOnTheBackWideLens() {
        assertEquals("back-wide", LensSelections.primaryOf(SettingsStore(context).current))
    }

    @Test
    fun theChosenLensIsStillChosenAfterTheStoreIsReopened() = runTest {
        val ultra = LensSource.lenses(real).single { it.id == "back-ultrawide" }.id
        SettingsStore(context).update { setLens(LensSelection.newBuilder().setPrimary(ultra)) }

        val restored = LensSelections.primaryOf(SettingsStore(context).current)

        assertEquals(ultra, restored)
        assertEquals(ultra, LensSource.lens(real, restored)?.id)
    }

    @Test
    fun aFacingRequestFromAnOlderViewerIsStoredAndAudited() = runTest {
        val store = SettingsStore(context)

        control(store).select(Lens.Facing.FRONT, "Y1TM-4JVY-9K8F", "viewer")

        assertEquals("front", LensSelections.primaryOf(store.current))
        val row = database.audit().all().single()
        assertEquals(AuditEvent.Kind.SETTINGS_CHANGED.number, row.kind)
        assertEquals("lens", row.detail)
        assertTrue("the chain still verifies", audit.verify().valid)
    }

    @Test
    fun aLensTheCameraDoesNotHaveIsRejectedAndNothingIsStored() = runTest {
        val store = SettingsStore(context)

        try {
            control(store).validated(LensSelection.newBuilder().setPrimary("back-macro").build())
            fail("expected a rejection")
        } catch (expected: RpcException) {
            assertEquals("back-wide", LensSelections.primaryOf(store.current))
        }
    }
}
