package com.homecam.app

import android.content.Context
import com.homecam.app.camera.CameraDiagnostics
import com.homecam.app.camera.CameraNode
import com.homecam.app.crypto.DeviceIdentity
import com.homecam.app.data.AuditLog
import com.homecam.app.data.HomecamDatabase
import com.homecam.app.data.SettingsStore
import com.homecam.app.viewer.ViewerNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one place the long-lived objects are constructed. Small enough that a DI framework
 * would be more machinery than the thing it wires up.
 */
class AppGraph private constructor(context: Context) {

    val app: Context = context.applicationContext
    val identity: DeviceIdentity = DeviceIdentity.get()
    val database: HomecamDatabase = HomecamDatabase.get(app)
    val audit: AuditLog = AuditLog(database.audit(), java.io.File(app.filesDir, "audit-head.bin"))
    val settings: SettingsStore = SettingsStore(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Persistent camera diagnostics for the device gates; one run id per process. */
    val diagnostics: CameraDiagnostics by lazy { CameraDiagnostics(app) }

    val cameraNode: CameraNode by lazy { CameraNode(this) }
    val viewerNode: ViewerNode by lazy { ViewerNode(this) }

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context).also { instance = it }
        }
    }
}
