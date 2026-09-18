package com.c1recorder.app.ble

import com.c1recorder.app.protocol.C1Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeC1BleScannerTest {

    private val c1Name = C1Protocol.Identity.DEVICE_NAME

    @Test
    fun startScan_whenBluetoothDisabled_setsDisabledStateAndDoesNotScan() {
        val scanner = FakeC1BleScanner().apply { bluetoothEnabled = false }

        scanner.startScan()

        assertEquals(ScanState.BLUETOOTH_DISABLED, scanner.scanState.value)
        assertTrue(scanner.discoveredDevices.value.isEmpty())
    }

    @Test
    fun startScan_whenPermissionDenied_setsPermissionDeniedState() {
        val scanner = FakeC1BleScanner().apply { permissionGranted = false }

        scanner.startScan()

        assertEquals(ScanState.PERMISSION_DENIED, scanner.scanState.value)
    }

    @Test
    fun startScan_thenResult_addsDeviceAndUpdatesRssiOnRepeatSighting() {
        val scanner = FakeC1BleScanner()
        scanner.startScan()

        scanner.emitScanResult(c1Name, "AA:BB:CC:DD:EE:FF", -70)
        scanner.emitScanResult(c1Name, "AA:BB:CC:DD:EE:FF", -50)

        assertEquals(1, scanner.discoveredDevices.value.size)
        assertEquals(-50, scanner.discoveredDevices.value[0].rssi)
    }

    @Test
    fun stopScan_thenResult_isIgnored() {
        val scanner = FakeC1BleScanner()
        scanner.startScan()
        scanner.stopScan()

        scanner.emitScanResult(c1Name, "AA:BB:CC:DD:EE:FF", -50)

        assertEquals(ScanState.IDLE, scanner.scanState.value)
        assertTrue(scanner.discoveredDevices.value.isEmpty())
    }

    @Test
    fun stopScan_setsIdleState() {
        val scanner = FakeC1BleScanner()
        scanner.startScan()

        scanner.stopScan()

        assertEquals(ScanState.IDLE, scanner.scanState.value)
    }
}
