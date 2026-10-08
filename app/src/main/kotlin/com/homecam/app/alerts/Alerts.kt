package com.homecam.app.alerts

import com.homecam.app.AppGraph
import com.homecam.core.detect.AlertThrottle
import com.homecam.core.health.HealthAlertPolicy
import com.homecam.core.health.HealthState
import com.homecam.proto.AlertPayload
import kotlinx.coroutines.launch

/**
 * The camera's alert fan-out. §2.7 wants a push to every paired viewer when a session
 * starts — the point being that if a device you don't recognise starts watching, your
 * phone buzzes. The viewer that started it is left out (D14). Detection alerts go to all,
 * once per visit.
 */
class Alerts(private val graph: AppGraph) {

    private val publisher = AlertPublisher()
    private val detectionThrottle = AlertThrottle()
    private val healthPolicy = HealthAlertPolicy()

    private enum class Kind(val label: String) { SESSION("session"), DETECTION("detection"), HEALTH("health") }

    fun onSessionStarted(viewerLabel: String, viewerFingerprint: String) {
        send(Kind.SESSION, except = viewerFingerprint) {
            base()
                .addLabels(AlertSubscriber.SESSION_LABEL)
                .addLabels("$viewerLabel ($viewerFingerprint)")
                .build()
        }
    }

    fun onDetection(labels: List<String>, clipId: String, thumbnailJpeg: ByteArray?) {
        if (!detectionThrottle.shouldAlert(labels, System.currentTimeMillis())) return
        send(Kind.DETECTION) {
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
        send(Kind.DETECTION) {
            base().addLabels(action).build()
        }
    }

    /** The camera protected itself, or stopped doing so (Phase 11). Says nothing about footage. */
    fun onHealth(transition: HealthState, previous: HealthState) {
        healthPolicy.onChange(previous, transition, System.currentTimeMillis()).forEach { alert ->
            send(Kind.HEALTH) { base().addLabels(alert.label).build() }
        }
    }

    private fun base(): AlertPayload.Builder = AlertPayload.newBuilder()
        .setAtMs(System.currentTimeMillis())
        .setCameraAlias(graph.settings.current.alias)
        .setCameraFingerprint(graph.identity.identity.shortFingerprint)

    private fun send(kind: Kind, except: String? = null, build: () -> AlertPayload) {
        graph.scope.launch {
            val recipients = graph.database.viewers().alertable()
                .filter {
                    when (kind) {
                        Kind.SESSION -> it.sessionAlerts
                        Kind.DETECTION -> it.detectionAlerts
                        Kind.HEALTH -> it.healthAlerts
                    }
                }
                .filter { it.fingerprint != except }
            if (recipients.isEmpty()) return@launch
            val payload = build()
            val statuses = recipients.map { publisher.publish(it, payload) }
            val delivered = statuses.count(AlertPublisher::delivered)
            val failure = statuses.firstOrNull { it != AlertPublisher.NOT_SENT && !AlertPublisher.delivered(it) }
            // The camera's half of GATE-F: whether it managed to post the alert at all. The
            // viewer arriving while locked (Doze) is checked on the receiving side.
            // A health label is a fixed token. Session and detection labels name people and
            // classes, and stay out of this line.
            val healthLabel = if (kind == Kind.HEALTH) payload.labelsList.firstOrNull() else null
            graph.diagnostics.alert(
                kind = kind.label,
                recipients = recipients.size,
                delivered = delivered,
                healthLabel = healthLabel,
                failureStatus = failure,
            )
        }
    }
}
