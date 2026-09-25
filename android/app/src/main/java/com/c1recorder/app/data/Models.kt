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

sealed interface SessionDownloadState {
    data object Idle : SessionDownloadState
    data class FetchingFiles(val sessionId: Long) : SessionDownloadState
    data class Downloading(
        val sessionId: Long,
        val fileId: Int,
        val bytesReceived: Long,
        val percent: Int,
    ) : SessionDownloadState

    /** CRC16 check against FILE_TAIL — mandatory, not advisory; a mismatch is reported as Error, never Completed. */
    data class Verifying(val sessionId: Long) : SessionDownloadState

    /** CELT decode + WAV write — the only stage after Verifying, so this covers both rather than inventing steps this app doesn't actually take separately. */
    data class Decoding(val sessionId: Long) : SessionDownloadState
    data class Completed(
        val sessionId: Long,
        val fileId: Int,
        val wavFile: java.io.File,
        val avcFile: java.io.File,
        val durationMs: Long,
        val crcVerified: Boolean,
    ) : SessionDownloadState
    data class Error(
        val sessionId: Long,
        val message: String,
    ) : SessionDownloadState
}
