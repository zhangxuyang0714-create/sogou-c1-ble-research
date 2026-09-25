package com.c1recorder.app.ble

import com.c1recorder.app.protocol.C1Protocol
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Connection lifecycle through the mandatory capability handshake. Per
 * docs/FINAL-investigation-summary.md, the handshake must be sent before any
 * other read/write gets a response, so the client performs it automatically
 * right after service discovery — there is no usable "connected but not
 * handshaken" state to expose. Only Ready is safe for Phase 5+ reads/writes.
 */
sealed interface C1ClientState {
    data object Disconnected : C1ClientState
    data object Connecting : C1ClientState
    data object DiscoveringServices : C1ClientState
    data object PerformingHandshake : C1ClientState
    data object Ready : C1ClientState
    data class ConnectionFailed(val reason: String) : C1ClientState
    data class MissingCharacteristics(val missing: List<String>) : C1ClientState
}

interface C1BleClient {
    val state: StateFlow<C1ClientState>
    fun connect(address: String)
    fun disconnect()

    // Only safe to call once state is Ready. Each call is its own
    // independently-timed-out operation; callers must await one before
    // starting the next — the underlying GATT connection only supports one
    // outstanding operation at a time.
    suspend fun readSerialNumber(): Result<String>
    suspend fun readFirmwareVersion(): Result<String>
    suspend fun readBatteryLevel(): Result<Int>
    suspend fun readState(): Result<Int>
    suspend fun readStorage(): Result<C1Protocol.StorageInfo>

    /** One page of the session list, starting at startSessionId (0 = first page). Callers paginate; see DefaultC1Repository.refreshSessions. */
    suspend fun getSessions(startSessionId: Long): Result<List<C1Protocol.SessionEntry>>

    // --- C1 Hardware Capability Test (docs/ANDROID-HARDWARE-CAPABILITY-TEST.md) ---
    // Investigative operations, not part of the confirmed v1 feature set.
    // Real success/failure judged by the official app's own criteria (e.g.
    // sessionId != 0 for startRealtime), not merely "a response arrived".

    suspend fun startRealtime(recordType: Int = C1Protocol.RecordType.COMMON): Result<C1Protocol.StartConfirm>
    suspend fun pauseRecord(): Result<C1Protocol.RecordStatusEvent>
    suspend fun stopRecord(): Result<C1Protocol.RecordStatusEvent>
    suspend fun getFiles(sessionId: Long, recordType: Int = C1Protocol.RecordType.COMMON): Result<List<C1Protocol.FileEntry>>

    /**
     * The full getFiles-must-already-have-succeeded download chain: subscribe
     * B001, send DOWNLOAD_FILE, wait HEADER, collect raw B001 notifies until
     * TAIL, verify CRC, send DOWNLOAD_STOP. See DownloadAttempt for how a
     * partial failure is distinguished from a full success.
     */
    suspend fun attemptDownload(sessionId: Long, fileId: Int, start: Long, end: Long, recordType: Int = C1Protocol.RecordType.COMMON): Result<DownloadAttempt>

    /**
     * Formal end-to-end BLE audio download:
     * 1. Multi-packet getFiles collection -> real fileId
     * 2. Subscribe B001 notification
     * 3. Opcode 8 (DOWNLOAD_FILE) -> Opcode 11 (FILE_HEADER, data[2] == 0)
     * 4. Receive raw B001 bytes for as long as it takes, with no idle cutoff
     * 5. Opcode 12 (FILE_TAIL) is mandatory: no tail before the hard timeout
     *    is always a Result.failure, never a fabricated success
     * 6. CRC16 mismatch against the tail is also always a Result.failure —
     *    crcVerified on the returned BleDownloadResult is therefore always
     *    true; it only exists to be displayed, not decided, by the caller
     * 7. Reconstruct .avc and decode (mono — see impl doc comment for the
     *    real-device evidence) to a 16kHz 16-bit WAV file
     *
     * onProgress fires only during step 4 (byte-level receive progress);
     * onStage fires for the two work phases after the stream ends (5-6 is
     * Verifying, 7 is Decoding) so the UI can show real stage state instead
     * of a byte percentage that stalls once the stream itself is done.
     */
    suspend fun downloadRecording(
        sessionId: Long,
        fileId: Int,
        durationMs: Long,
        destinationDir: java.io.File,
        onProgress: ((BleDownloadProgress) -> Unit)? = null,
        onStage: ((DownloadStage) -> Unit)? = null,
    ): Result<BleDownloadResult>
}

enum class DownloadStage { VERIFYING, DECODING }

data class BleDownloadProgress(
    val sessionId: Long,
    val fileId: Int,
    val bytesReceived: Long,
    val totalBytesExpected: Long,
    val packetCount: Int,
    val percent: Int,
)

data class BleDownloadResult(
    val sessionId: Long,
    val fileId: Int,
    val rawAvcFile: java.io.File,
    val wavFile: java.io.File,
    val bytesReceived: Long,
    val packetCount: Int,
    val crc16: Int?,
    val crcVerified: Boolean,
)

/**
 * Full diagnostic record of one attemptDownload() call — deliberately not
 * collapsed into a single success/failure boolean, so a caller (and
 * docs/ANDROID-HARDWARE-CAPABILITY-TEST.md) can tell exactly which stage
 * failed instead of a generic "download failed".
 */
data class DownloadAttempt(
    val headerOk: Boolean?,
    val bytesReceived: Int,
    val tail: C1Protocol.FileTail?,
    val computedCrc: Int?,
) {
    val crcMatches: Boolean? get() = if (tail != null && computedCrc != null) tail.crc16 == computedCrc else null
}

/**
 * The characteristics every later phase depends on. Checked right after
 * service discovery so a device missing one of these fails fast and
 * visibly, instead of surfacing as a confusing timeout deep in Phase 4/5/6.
 */
object RequiredCharacteristics {
    val all: List<Pair<String, UUID>> = listOf(
        "cmdWrite" to C1Protocol.Characteristic.CMD_WRITE,
        "cmdIndicate" to C1Protocol.Characteristic.CMD_INDICATE,
        "firmwareVersion" to C1Protocol.Characteristic.FIRMWARE_VERSION,
        "serialNumber" to C1Protocol.Characteristic.SERIAL_NUMBER,
        "state" to C1Protocol.Characteristic.STATE,
        "batteryLevel" to C1Protocol.Characteristic.BATTERY_LEVEL,
    )

    fun findMissing(discovered: Set<UUID>): List<String> =
        all.filter { it.second !in discovered }.map { it.first }
}
