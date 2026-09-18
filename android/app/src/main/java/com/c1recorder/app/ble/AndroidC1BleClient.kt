package com.c1recorder.app.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.c1recorder.app.protocol.C1Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "C1Ble"
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val HANDSHAKE_TIMEOUT_MS = 10_000L

class AndroidC1BleClient(
    private val context: Context,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) : C1BleClient {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _state = MutableStateFlow<C1ClientState>(C1ClientState.Disconnected)
    override val state: StateFlow<C1ClientState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var cmdWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var cmdIndicateCharacteristic: BluetoothGattCharacteristic? = null

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
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
            val byUuid = g.services.flatMap { it.characteristics }.associateBy { it.uuid }
            val missing = RequiredCharacteristics.findMissing(byUuid.keys)
            if (missing.isNotEmpty()) {
                Log.w(TAG, "missing characteristics: $missing")
                _state.value = C1ClientState.MissingCharacteristics(missing)
                return
            }
            cmdWriteCharacteristic = byUuid.getValue(C1Protocol.Characteristic.CMD_WRITE)
            cmdIndicateCharacteristic = byUuid.getValue(C1Protocol.Characteristic.CMD_INDICATE)
            startHandshake(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != C1Protocol.Descriptor.CLIENT_CHARACTERISTIC_CONFIG) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cleanupAfterFailure("订阅通知失败 status=$status")
                return
            }
            val writeChar = cmdWriteCharacteristic ?: return cleanupAfterFailure("内部错误: cmdWrite 特征值丢失")
            val packet = C1Protocol.buildCapabilityInitFrame()
            val sent = try {
                writeChar.value = packet
                g.writeCharacteristic(writeChar)
            } catch (e: SecurityException) {
                cleanupAfterFailure("缺少 BLUETOOTH_CONNECT 权限")
                return
            }
            if (!sent) {
                cleanupAfterFailure("发送握手包失败")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != C1Protocol.Characteristic.CMD_WRITE) return
            if (_state.value != C1ClientState.PerformingHandshake) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cleanupAfterFailure("握手包写入失败 status=$status")
            }
            // else: wait for the indicate on cmdIndicate — that's the actual handshake ack.
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != C1Protocol.Characteristic.CMD_INDICATE) return
            if (_state.value != C1ClientState.PerformingHandshake) return
            val opcode = C1Protocol.parseResponseOpcode(characteristic.value ?: ByteArray(0))
            Log.i(TAG, "handshake ack, opcode=$opcode")
            clearTimeout()
            _state.value = C1ClientState.Ready
        }
    }

    // minSdk 31 lacks the API 33+ writeDescriptor(descriptor, value) overload.
    @Suppress("DEPRECATION")
    private fun startHandshake(g: BluetoothGatt) {
        val indicateChar = cmdIndicateCharacteristic ?: return cleanupAfterFailure("内部错误: cmdIndicate 特征值丢失")
        _state.value = C1ClientState.PerformingHandshake
        try {
            if (!g.setCharacteristicNotification(indicateChar, true)) {
                cleanupAfterFailure("无法启用通知")
                return
            }
            val cccd = indicateChar.getDescriptor(C1Protocol.Descriptor.CLIENT_CHARACTERISTIC_CONFIG)
                ?: return cleanupAfterFailure("设备缺少 CCCD 描述符")
            cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (!g.writeDescriptor(cccd)) {
                cleanupAfterFailure("订阅通知请求失败")
                return
            }
        } catch (e: SecurityException) {
            cleanupAfterFailure("缺少 BLUETOOTH_CONNECT 权限")
            return
        }
        timeoutRunnable = Runnable {
            Log.w(TAG, "handshake timeout")
            cleanupAfterFailure("握手超时")
        }.also { timeoutHandler.postDelayed(it, handshakeTimeoutMs) }
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
        cmdWriteCharacteristic = null
        cmdIndicateCharacteristic = null
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}
