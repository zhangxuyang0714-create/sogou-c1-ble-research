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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeoutException

private const val TAG = "C1Ble"
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val HANDSHAKE_TIMEOUT_MS = 10_000L
private const val OPERATION_TIMEOUT_MS = 8_000L

class AndroidC1BleClient(
    private val context: Context,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    private val operationTimeoutMs: Long = OPERATION_TIMEOUT_MS,
) : C1BleClient {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _state = MutableStateFlow<C1ClientState>(C1ClientState.Disconnected)
    override val state: StateFlow<C1ClientState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var cmdWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var cmdIndicateCharacteristic: BluetoothGattCharacteristic? = null
    private var firmwareVersionCharacteristic: BluetoothGattCharacteristic? = null
    private var serialNumberCharacteristic: BluetoothGattCharacteristic? = null
    private var stateCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryLevelCharacteristic: BluetoothGattCharacteristic? = null

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    // The GATT connection only allows one outstanding operation at a time;
    // callers of readXxx()/readStorage() must await each before starting the
    // next. These two fields track whichever single operation is in flight.
    private var pendingRead: CancellableContinuation<Result<ByteArray>>? = null
    private var pendingIndicate: CancellableContinuation<Result<ByteArray>>? = null
    private var pendingIndicateExpectedOpcodes: Set<Int> = emptySet()

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
            firmwareVersionCharacteristic = byUuid.getValue(C1Protocol.Characteristic.FIRMWARE_VERSION)
            serialNumberCharacteristic = byUuid.getValue(C1Protocol.Characteristic.SERIAL_NUMBER)
            stateCharacteristic = byUuid.getValue(C1Protocol.Characteristic.STATE)
            batteryLevelCharacteristic = byUuid.getValue(C1Protocol.Characteristic.BATTERY_LEVEL)
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
            when (_state.value) {
                C1ClientState.PerformingHandshake -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cleanupAfterFailure("握手包写入失败 status=$status")
                    }
                    // else: wait for the indicate on cmdIndicate — that's the actual handshake ack.
                }
                C1ClientState.Ready -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        val cont = pendingIndicate
                        pendingIndicate = null
                        cont?.resume(Result.failure(IOException("写入失败 status=$status")), onCancellation = null)
                    }
                    // else: wait for the matching indicate in onCharacteristicChanged.
                }
                else -> Unit
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != C1Protocol.Characteristic.CMD_INDICATE) return
            val data = characteristic.value ?: ByteArray(0)
            when (_state.value) {
                C1ClientState.PerformingHandshake -> {
                    Log.i(TAG, "handshake ack, opcode=${C1Protocol.parseResponseOpcode(data)}")
                    clearTimeout()
                    _state.value = C1ClientState.Ready
                }
                C1ClientState.Ready -> {
                    val opcode = C1Protocol.parseResponseOpcode(data)
                    if (opcode !in pendingIndicateExpectedOpcodes) {
                        Log.i(TAG, "ignoring indicate opcode=$opcode, waiting for $pendingIndicateExpectedOpcodes")
                        return
                    }
                    val cont = pendingIndicate
                    pendingIndicate = null
                    cont?.resume(Result.success(data), onCancellation = null)
                }
                else -> Unit
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val cont = pendingRead ?: return
            pendingRead = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cont.resume(Result.success(characteristic.value ?: ByteArray(0)), onCancellation = null)
            } else {
                cont.resume(Result.failure(IOException("读取失败 status=$status")), onCancellation = null)
            }
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

    override suspend fun readSerialNumber(): Result<String> =
        readCharacteristicRaw(serialNumberCharacteristic, "SN").map { C1Protocol.parseSerialNumber(it) }

    override suspend fun readFirmwareVersion(): Result<String> =
        readCharacteristicRaw(firmwareVersionCharacteristic, "固件版本").mapCatching {
            C1Protocol.parseFirmwareVersion(it) ?: error("固件版本格式异常")
        }

    override suspend fun readBatteryLevel(): Result<Int> =
        readCharacteristicRaw(batteryLevelCharacteristic, "电量").mapCatching {
            C1Protocol.parseBatteryLevel(it) ?: error("电量数据为空")
        }

    override suspend fun readState(): Result<Int> =
        readCharacteristicRaw(stateCharacteristic, "设备状态").mapCatching {
            C1Protocol.parseStateRaw(it) ?: error("状态数据格式异常")
        }

    override suspend fun readStorage(): Result<C1Protocol.StorageInfo> =
        sendCommandAndAwaitIndicate(
            frame = C1Protocol.buildGetStorageFrame(),
            expectedOpcodes = setOf(C1Protocol.ResponseOpcode.GET_STORAGE_CONFIRM),
            label = "存储信息",
        ).mapCatching { C1Protocol.parseStorageResponse(it) ?: error("存储信息格式异常") }

    @Suppress("DEPRECATION")
    private suspend fun readCharacteristicRaw(characteristic: BluetoothGattCharacteristic?, label: String): Result<ByteArray> {
        val g = gatt ?: return Result.failure(IllegalStateException("未连接"))
        val target = characteristic ?: return Result.failure(IllegalStateException("$label 特征值不存在"))
        if (_state.value != C1ClientState.Ready) return Result.failure(IllegalStateException("设备未就绪"))
        if (pendingRead != null || pendingIndicate != null) {
            return Result.failure(IllegalStateException("已有操作进行中"))
        }

        val result = withTimeoutOrNull(operationTimeoutMs) {
            suspendCancellableCoroutine { cont ->
                pendingRead = cont
                val started = try {
                    g.readCharacteristic(target)
                } catch (e: SecurityException) {
                    false
                }
                if (!started) {
                    pendingRead = null
                    cont.resume(Result.failure(IllegalStateException("$label 读取请求失败")), onCancellation = null)
                }
                cont.invokeOnCancellation { pendingRead = null }
            }
        }
        if (result == null) {
            pendingRead = null
            return Result.failure(TimeoutException("$label 读取超时"))
        }
        return result
    }

    @Suppress("DEPRECATION")
    private suspend fun sendCommandAndAwaitIndicate(frame: ByteArray, expectedOpcodes: Set<Int>, label: String): Result<ByteArray> {
        val g = gatt ?: return Result.failure(IllegalStateException("未连接"))
        val writeChar = cmdWriteCharacteristic ?: return Result.failure(IllegalStateException("cmdWrite 特征值不存在"))
        if (_state.value != C1ClientState.Ready) return Result.failure(IllegalStateException("设备未就绪"))
        if (pendingRead != null || pendingIndicate != null) {
            return Result.failure(IllegalStateException("已有操作进行中"))
        }

        val result = withTimeoutOrNull(operationTimeoutMs) {
            suspendCancellableCoroutine { cont ->
                pendingIndicate = cont
                pendingIndicateExpectedOpcodes = expectedOpcodes
                writeChar.value = frame
                val started = try {
                    g.writeCharacteristic(writeChar)
                } catch (e: SecurityException) {
                    false
                }
                if (!started) {
                    pendingIndicate = null
                    cont.resume(Result.failure(IllegalStateException("$label 请求发送失败")), onCancellation = null)
                }
                cont.invokeOnCancellation { pendingIndicate = null }
            }
        }
        if (result == null) {
            pendingIndicate = null
            return Result.failure(TimeoutException("$label 超时"))
        }
        return result
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
        firmwareVersionCharacteristic = null
        serialNumberCharacteristic = null
        stateCharacteristic = null
        batteryLevelCharacteristic = null

        // GATT callbacks can run on a different thread than whoever called
        // disconnect(), so a pending continuation may already have been
        // resumed by the time we get here — resume is not safe to call twice.
        val readCont = pendingRead
        pendingRead = null
        readCont?.let { safeResume(it, Result.failure(IOException("连接已断开"))) }

        val indicateCont = pendingIndicate
        pendingIndicate = null
        indicateCont?.let { safeResume(it, Result.failure(IOException("连接已断开"))) }
    }

    private fun safeResume(cont: CancellableContinuation<Result<ByteArray>>, value: Result<ByteArray>) {
        try {
            cont.resume(value) { _, _, _ -> }
        } catch (e: IllegalStateException) {
            // Already resumed/cancelled from the GATT callback thread — fine, ignore.
        }
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}
