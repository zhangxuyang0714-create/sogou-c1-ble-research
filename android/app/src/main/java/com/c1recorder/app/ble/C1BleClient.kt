package com.c1recorder.app.ble

import com.c1recorder.app.protocol.C1Protocol
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * GATT connection lifecycle only: connect, discover services, confirm the
 * characteristics this app needs are present. No reads/writes yet — those
 * start in Phase 4 (capability handshake) and Phase 5 (device info).
 */
sealed interface C1ClientState {
    data object Disconnected : C1ClientState
    data object Connecting : C1ClientState
    data object DiscoveringServices : C1ClientState
    data object Connected : C1ClientState
    data class ConnectionFailed(val reason: String) : C1ClientState
    data class MissingCharacteristics(val missing: List<String>) : C1ClientState
}

interface C1BleClient {
    val state: StateFlow<C1ClientState>
    fun connect(address: String)
    fun disconnect()
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
