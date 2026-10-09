package com.homecam.app.camera

import com.homecam.app.data.AuditLog
import com.homecam.app.data.SettingsStore
import com.homecam.core.lens.LensSelections
import com.homecam.core.rpc.RpcException
import com.homecam.proto.AuditEvent
import com.homecam.proto.Lens
import com.homecam.proto.LensSelection
import com.homecam.proto.RpcError

/** Which lens the camera looks through is a setting, so changing it is audited like one. */
class LensControl(
    private val settings: SettingsStore,
    private val audit: AuditLog,
    private val advertised: () -> Collection<String>,
) {

    fun validated(selection: LensSelection): LensSelection {
        LensSelections.rejection(selection, advertised())?.let { throw RpcException(RpcError.Code.INVALID_ARGUMENT, it) }
        return selection
    }

    /** The facing-only control older viewers send: stored and audited as the setting it stands for. */
    suspend fun select(facing: Lens.Facing, viewerFingerprint: String, viewerLabel: String) {
        val selection = validated(LensSelection.newBuilder().setPrimary(LensSelections.idFor(facing)).build())
        settings.update { setLens(selection) }
        audit.record(
            AuditEvent.Kind.SETTINGS_CHANGED,
            viewerFingerprint = viewerFingerprint,
            viewerLabel = viewerLabel,
            detail = AUDIT_DETAIL,
        )
    }

    companion object {
        const val AUDIT_DETAIL = "lens"
    }
}
