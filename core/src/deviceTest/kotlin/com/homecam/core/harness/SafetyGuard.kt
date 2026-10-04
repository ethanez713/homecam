package com.homecam.core.harness

import com.homecam.core.crypto.ConstantTime

/**
 * Roster size and `service_start` lines taken before any throwaway is paired, and the
 * reason a flood has to stop. Every scenario runs inside [run], so the cleanup step
 * still revokes A and B when the scenario itself fails.
 */
internal class SafetyGuard {
    val adb = CameraAdb(DeviceConfig.serial())
    val host: String = DeviceConfig.host()
    val port: Int = DeviceConfig.port()

    var pin: ByteArray? = null
    var viewerA: ThrowawayViewer? = null
    var viewerB: ThrowawayViewer? = null
    var clientA: HarnessClient? = null
    var clientB: HarnessClient? = null
    var baseline: Baseline? = null

    class Baseline(
        val roster: Int,
        val serviceStarts: Int,
        val serviceLines: Set<String>,
        val blindLines: Set<String>,
    )

    suspend fun run(block: suspend SafetyGuard.() -> Unit) {
        var primary: Throwable? = null
        try {
            val jsonl = adb.cameraJsonl()
            baseline = Baseline(
                roster = adb.rosterCount(),
                serviceStarts = countEvent(jsonl, "service_start"),
                serviceLines = eventLines(jsonl, "service_start"),
                blindLines = eventLines(jsonl, "camera_blind"),
            )
            block()
        } catch (t: Throwable) {
            primary = t
        }
        val cleanup = try {
            Sec5Cleanup().run(this, scenarioFinished = primary == null)
            null
        } catch (t: Throwable) {
            t
        }
        if (primary != null) {
            if (cleanup != null) primary.addSuppressed(cleanup)
            throw primary
        }
        if (cleanup != null) throw cleanup
    }

    /** Arms the debug receiver, reads its invite, and pairs [label]. The pin is the invite's camera key. */
    suspend fun pair(label: String): HarnessClient {
        if (label == LABEL_A) clientA?.drop()
        val viewer = ThrowawayViewer.generate(label)
        val broadcast = adb.armPairing()
        check(broadcast.contains("data=\"armed\"")) { "pairing arm failed: ${broadcast.trim()}" }
        val invite = DeviceConfig.decodeInvite(adb.readInvite())
        val key = invite.cameraPublicKey.toByteArray()
        val known = pin
        if (known == null) pin = key else check(ConstantTime.equals(known, key)) { "camera identity changed mid-run" }
        val client = HarnessClient(host, port, viewer, key)
        client.pair(invite)
        if (label == LABEL_A) {
            viewerA = viewer
            clientA = client
        } else {
            viewerB = viewer
            clientB = client
        }
        return client
    }

    fun requirePin(): ByteArray = pin ?: error("no invite has been read")

    /** A `camera_blind` or `service_start` line that was not in the baseline, or null. */
    fun freshProblem(jsonl: String): String? {
        val base = baseline ?: return null
        val blind = eventLines(jsonl, "camera_blind") - base.blindLines
        if (blind.isNotEmpty()) return "camera_blind: ${blind.first()}"
        val started = eventLines(jsonl, "service_start") - base.serviceLines
        if (started.isNotEmpty()) return "service_start: ${started.first()}"
        return null
    }

    companion object {
        const val LABEL_A = "gate-sec-a"
        const val LABEL_B = "gate-sec-b"
    }
}

/** The camera restarted, or the diagnostics log could not be read, so a flood must stop. */
internal class SafetyAbort(val reason: String) : RuntimeException(reason)

/**
 * Revoke the throwaways this run created, then require the camera to look as it did at the start.
 * B goes last because it is the connection that revokes A.
 */
internal class Sec5Cleanup {
    suspend fun run(guard: SafetyGuard, scenarioFinished: Boolean) {
        val notes = StringBuilder()
        var revokeOk = true
        if (scenarioFinished && (guard.viewerA == null || guard.viewerB == null)) {
            revokeOk = false
            notes.append("scenario finished without both throwaways\n")
        }
        val b = guard.clientB
        val aFingerprint = guard.viewerA?.fingerprint
        if (b != null) {
            if (!b.isConnected()) {
                val connected = runCatching { b.connect() }
                if (connected.isFailure) {
                    revokeOk = false
                    notes.append("reconnect B: ${connected.exceptionOrNull()?.message}\n")
                }
            }
            if (aFingerprint != null) {
                if (b.isConnected()) {
                    val revoked = runCatching { b.revoke(aFingerprint) }
                    if (revoked.getOrNull() != true) {
                        revokeOk = false
                        notes.append("revoke A: ${revoked.exceptionOrNull()?.message ?: revoked.getOrNull()}\n")
                    } else {
                        notes.append("revoked A\n")
                    }
                } else {
                    revokeOk = false
                    notes.append("B was not connected to revoke A\n")
                }
            }
            if (b.isConnected()) {
                val revoked = runCatching { b.revoke() }
                if (revoked.getOrNull() != true) {
                    revokeOk = false
                    notes.append("revoke B: ${revoked.exceptionOrNull()?.message ?: revoked.getOrNull()}\n")
                } else {
                    notes.append("revoked B last\n")
                }
            } else if (guard.viewerB != null) {
                revokeOk = false
                notes.append("B was not connected to revoke itself\n")
            }
        } else if (guard.viewerA != null || guard.viewerB != null) {
            revokeOk = false
            notes.append("B's client is missing\n")
        }
        guard.clientA?.drop()
        guard.clientB?.drop()

        val base = guard.baseline
        val roster = runCatching { guard.adb.rosterCount() }
        val starts = runCatching { guard.adb.serviceStartCount() }
        val inviteGone = runCatching {
            guard.adb.deleteInvite()
            !guard.adb.invitePresent()
        }
        val tempsGone = guard.adb.deleteTemps()
        roster.exceptionOrNull()?.let { notes.append("roster: ${it.message}\n") }
        starts.exceptionOrNull()?.let { notes.append("service_start: ${it.message}\n") }
        inviteGone.exceptionOrNull()?.let { notes.append("invite: ${it.message}\n") }

        val ok = revokeOk && base != null &&
            roster.getOrNull() == base.roster &&
            starts.getOrNull() == base.serviceStarts &&
            inviteGone.getOrNull() == true &&
            tempsGone
        val evidence = buildString {
            append("roster ").append(base?.roster).append(" -> ").append(roster.getOrNull())
            append(", service_start ").append(base?.serviceStarts).append(" -> ").append(starts.getOrNull())
            append(", invite removed=").append(inviteGone.getOrNull())
            append(", pulled files removed=").append(tempsGone)
            if (notes.isNotEmpty()) {
                append('\n')
                append(notes)
            }
        }
        assertPass(PASS, ok, evidence)
    }

    private companion object {
        const val PASS = "Revoke A and B (B revokes itself last), confirm the roster and " +
            "service_start count match the start, and delete the harness's pulled files."
    }
}
