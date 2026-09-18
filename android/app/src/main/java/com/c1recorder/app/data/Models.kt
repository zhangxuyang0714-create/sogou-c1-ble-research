package com.c1recorder.app.data

data class C1Device(
    val address: String,
    val name: String? = null,
    val sn: String? = null,
    val firmware: String? = null,
    val batteryPercent: Int? = null,
    val stateRaw: Int? = null,
    val totalStorageKB: Long? = null,
    val freeStorageKB: Long? = null,
)

/**
 * A recording session as reported by the device's session list (getSessions).
 * thirdFieldUnknown mirrors C1Protocol.SessionEntry.thirdFieldUnknown — its
 * meaning is not established, so it is not renamed to something like
 * "recordType" until docs/FINAL-investigation-summary.md says otherwise.
 */
data class RecordingSession(
    val sessionId: Long,
    val durationMs: Long,
    val thirdFieldUnknown: Long,
)
