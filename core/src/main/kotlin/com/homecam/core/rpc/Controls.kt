package com.homecam.core.rpc

/**
 * Method ids, scoped to a service. Allocated in sparse blocks — 0-15 core, 16-31
 * detection, 32-47 storage, 64+ later additions — so a decade of features can be added
 * without renumbering anything (§4.2).
 */
object Controls {
    object Session {
        const val CONFIG = 0
        const val PUSH_TO_TALK = 1
        const val MANUAL_RECORD = 2

        // NOTIFICATION only: the camera pushes it to the requester when a manual clip
        // closes. No responder is registered, so the completeness sweep expects it absent.
        const val MANUAL_RECORD_RESULT = 3
        const val TORCH = 4
        const val ORIENTATION = 5
        const val RESOLUTION = 8
        const val ZOOM = 9
        const val LENS = 10
        const val SNAPSHOT = 12
    }

    object Capability {
        const val EXCHANGE = 0
    }

    object Settings {
        const val SNAPSHOT = 0
        const val ARMED = 1
        const val ALIAS = 2
        const val MICROPHONE = 3
        const val LOW_LIGHT = 4
        const val DETECTION_MODE = 16
        const val DETECTION_SCHEDULE = 17
        const val DETECTION_ZONE = 18
        const val PRIVACY_MASK = 19
        const val BOUNDING_BOX_OVERLAY = 22
        const val RETENTION = 32
        const val RECORDING_MODE = 33
    }

    object Status {
        const val RUNTIME = 0
        const val SESSION = 1
    }

    object Media {
        const val CLIP_LIST = 1
        const val CLIP_DELETE = 2
        const val THUMBNAIL = 3
        const val CLIP_DATA = 4
        const val DATE_LIST = 5
        const val TIMELINE = 6
        const val CLIP_PIN = 7
    }

    object Audit {
        const val QUERY = 0

        // Not a request handler: the camera PUSHes this as a NOTIFICATION when an audited
        // event happens, so a connected viewer sees the log grow live. There is nothing to
        // register a responder for, and the audit-completeness sweep expects it absent.
        const val EVENT = 1
    }

    object Access {
        const val VIEWER_LIST = 0
        const val REVOKE_VIEWER = 1

        // Deliberately has no over-the-wire handler: pairing mode is armed on the camera's
        // own screen and this id is camera-UI-local (the PairingMode message even carries
        // the raw invite). Answering it to a remote caller would let a paired viewer put
        // the camera into pairing mode, which must stay a physical-access action (§2.2).
        const val PAIRING_MODE = 2
        const val ALERT_REGISTRATION = 3
    }
}
