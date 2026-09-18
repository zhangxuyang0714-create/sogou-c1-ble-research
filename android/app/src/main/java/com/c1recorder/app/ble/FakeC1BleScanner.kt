package com.c1recorder.app.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Test/dev double for C1BleScanner — no android.bluetooth.* involved, so it
 * runs on the plain JVM in unit tests and lets Compose UI be developed
 * without a real device or an emulator.
 */
class FakeC1BleScanner : C1BleScanner {
    private val _scanState = MutableStateFlow(ScanState.IDLE)
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<ScannedDevice>> = _discoveredDevices.asStateFlow()

    var bluetoothEnabled: Boolean = true
    var permissionGranted: Boolean = true

    override fun startScan() {
        if (!bluetoothEnabled) {
            _scanState.value = ScanState.BLUETOOTH_DISABLED
            return
        }
        if (!permissionGranted) {
            _scanState.value = ScanState.PERMISSION_DENIED
            return
        }
        _discoveredDevices.value = emptyList()
        _scanState.value = ScanState.SCANNING
    }

    override fun stopScan() {
        if (_scanState.value == ScanState.SCANNING) {
            _scanState.value = ScanState.IDLE
        }
    }

    /** Test helper: simulate a scan callback firing while scanning is active. */
    fun emitScanResult(name: String?, address: String, rssi: Int) {
        if (_scanState.value != ScanState.SCANNING) return
        _discoveredDevices.update { ScanResultReducer.accept(it, name, address, rssi) }
    }
}
