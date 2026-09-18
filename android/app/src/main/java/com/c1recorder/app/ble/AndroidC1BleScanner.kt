package com.c1recorder.app.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "C1Ble"
private const val SCAN_TIMEOUT_MS = 15_000L

class AndroidC1BleScanner(
    private val context: Context,
    private val scanTimeoutMs: Long = SCAN_TIMEOUT_MS,
) : C1BleScanner {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter

    private val _scanState = MutableStateFlow(ScanState.IDLE)
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<ScannedDevice>> = _discoveredDevices.asStateFlow()

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = readDeviceNameOrNull(result)
            _discoveredDevices.update { ScanResultReducer.accept(it, name, result.device.address, result.rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed, errorCode=$errorCode")
            clearTimeout()
            _scanState.value = ScanState.IDLE
        }
    }

    private fun readDeviceNameOrNull(result: ScanResult): String? {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return null
        return try {
            result.device.name
        } catch (e: SecurityException) {
            null
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun startScan() {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _scanState.value = ScanState.BLUETOOTH_DISABLED
            return
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            _scanState.value = ScanState.PERMISSION_DENIED
            return
        }
        val bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            _scanState.value = ScanState.BLUETOOTH_DISABLED
            return
        }

        _discoveredDevices.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bleScanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "startScan denied", e)
            _scanState.value = ScanState.PERMISSION_DENIED
            return
        }
        _scanState.value = ScanState.SCANNING
        timeoutRunnable = Runnable { stopScan() }.also {
            timeoutHandler.postDelayed(it, scanTimeoutMs)
        }
    }

    override fun stopScan() {
        clearTimeout()
        if (_scanState.value != ScanState.SCANNING) return
        if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: SecurityException) {
                Log.w(TAG, "stopScan denied", e)
            }
        }
        _scanState.value = ScanState.IDLE
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}
