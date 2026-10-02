package com.homecam.app.alerts

import com.homecam.app.AppGraph
import com.homecam.proto.AlertPayload
import kotlinx.coroutines.launch

/**
 * The camera's alert fan-out. §2.7 wants a push to every paired viewer when a session
 * starts — the point being that if a device you don't recognise starts watching, your
 * phone buzzes. The viewer that started it is left out (D14). Detection alerts go to all.
 */
class Alerts(private val graph: AppGraph) {

    private val publisher = AlertPublisher()

    fun onSessionStarted(viewerLabel: String, viewerFingerprint: String) {
        send(sessionAlert = true, except = viewerFingerprint) {
            base()
                .addLabels(AlertSubscriber.SESSION_LABEL)
                .addLabels("$viewerLabel ($viewerFingerprint)")
                .build()
        }
    }

    fun onDetection(labels: List<String>, clipId: String, thumbnailJpeg: ByteArray?) {
        send(sessionAlert = false) {
            base()
                .addAllLabels(labels)
                .setClipId(clipId)
                .apply {
                    if (thumbnailJpeg != null) {
                        setThumbnailJpeg(com.google.protobuf.ByteString.copyFrom(thumbnailJpeg))
                    }
                }
                .build()
        }
    }

    /** A response action fired (Phase 8), so the alert says what happened, not just what was seen. */
    fun onResponseAction(action: String) {
        send(sessionAlert = false) {
            base().addLabels(action).build()
        }
    }

    private fun base(): AlertPayload.Builder = AlertPayload.newBuilder()
        .setAtMs(System.currentTimeMillis())
        .setCameraAlias(graph.settings.current.alias)
        .setCameraFingerprint(graph.identity.identity.shortFingerprint)

    private fun send(sessionAlert: Boolean, except: String? = null, build: () -> AlertPayload) {
        graph.scope.launch {
            val recipients = graph.database.viewers().alertable()
                .filter { if (sessionAlert) it.sessionAlerts else it.detectionAlerts }
                .filter { it.fingerprint != except }
            if (recipients.isEmpty()) return@launch
            val payload = build()
            val delivered = recipients.count { publisher.publish(it, payload) }
            // The camera's half of GATE-F: whether it managed to post the alert at all. The
            // viewer arriving while locked (Doze) is checked on the receiving side.
            graph.diagnostics.alert(
                kind = if (sessionAlert) "session" else "detection",
                recipients = recipients.size,
                delivered = delivered,
            )
        }
    }
}
