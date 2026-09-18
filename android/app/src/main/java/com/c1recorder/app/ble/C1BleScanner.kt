package com.c1recorder.app.ble

import kotlinx.coroutines.flow.StateFlow

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

enum class ScanState {
    IDLE,
    SCANNING,
    BLUETOOTH_DISABLED,
    PERMISSION_DENIED,
}

/**
 * Discovery only — no GATT connection. Independent of whatever connects to
 * the device afterwards (C1Repository / a future C1BleClient).
 */
interface C1BleScanner {
    val scanState: StateFlow<ScanState>
    val discoveredDevices: StateFlow<List<ScannedDevice>>

    fun startScan()
    fun stopScan()
}

/**
 * Pure logic shared by the real scanner and the fake: filter to the C1's
 * advertised name and dedup by address, replacing the entry so RSSI stays
 * current. Kept separate from ScanCallback so it's testable on the plain JVM.
 */
object ScanResultReducer {
    fun accept(
        current: List<ScannedDevice>,
        candidateName: String?,
        address: String,
        rssi: Int,
    ): List<ScannedDevice> {
        if (candidateName != com.c1recorder.app.protocol.C1Protocol.Identity.DEVICE_NAME) return current
        val updated = ScannedDevice(candidateName, address, rssi)
        val existingIndex = current.indexOfFirst { it.address == address }
        return if (existingIndex >= 0) {
            current.toMutableList().apply { this[existingIndex] = updated }
        } else {
            current + updated
        }
    }
}
