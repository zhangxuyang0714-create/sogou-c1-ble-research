package com.c1recorder.app.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "C1Ble"
private const val CONNECT_TIMEOUT_MS = 15_000L

class AndroidC1BleClient(
    private val context: Context,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : C1BleClient {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _state = MutableStateFlow<C1ClientState>(C1ClientState.Disconnected)
    override val state: StateFlow<C1ClientState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cleanupAfterFailure("connect status=$status")
                        return
                    }
                    clearTimeout()
                    _state.value = C1ClientState.DiscoveringServices
                    try {
                        g.discoverServices()
                    } catch (e: SecurityException) {
                        cleanupAfterFailure("缺少 BLUETOOTH_CONNECT 权限")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clearTimeout()
                    closeGatt()
                    _state.value = C1ClientState.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cleanupAfterFailure("discoverServices status=$status")
                return
            }
            val discoveredUuids = g.services.flatMap { it.characteristics }.map { it.uuid }.toSet()
            val missing = RequiredCharacteristics.findMissing(discoveredUuids)
            _state.value = if (missing.isEmpty()) {
                C1ClientState.Connected
            } else {
                Log.w(TAG, "missing characteristics: $missing")
                C1ClientState.MissingCharacteristics(missing)
            }
        }
    }

    // The Executor/BluetoothGattConnectionSettings connectGatt overload that
    // replaces this one is API 37+; our minSdk/targetSdk (31/36) and the real
    // test device (API 36) don't have it, so the deprecated overload is the
    // only usable choice.
    @Suppress("DEPRECATION")
    override fun connect(address: String) {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            _state.value = C1ClientState.ConnectionFailed("蓝牙不可用")
            return
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            _state.value = C1ClientState.ConnectionFailed("缺少 BLUETOOTH_CONNECT 权限")
            return
        }
        closeGatt()

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _state.value = C1ClientState.ConnectionFailed("无效地址: $address")
            return
        }

        _state.value = C1ClientState.Connecting
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _state.value = C1ClientState.ConnectionFailed("缺少 BLUETOOTH_CONNECT 权限")
            return
        }
        timeoutRunnable = Runnable {
            Log.w(TAG, "connect timeout for $address")
            cleanupAfterFailure("连接超时")
        }.also { timeoutHandler.postDelayed(it, connectTimeoutMs) }
    }

    override fun disconnect() {
        clearTimeout()
        closeGatt()
        _state.value = C1ClientState.Disconnected
    }

    private fun cleanupAfterFailure(reason: String) {
        clearTimeout()
        closeGatt()
        _state.value = C1ClientState.ConnectionFailed(reason)
    }

    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "disconnect/close denied", e)
        }
        gatt = null
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}
