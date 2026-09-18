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
