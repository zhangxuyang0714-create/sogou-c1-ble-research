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
import com.c1recorder.app.audio.C1AudioDecoder
import com.c1recorder.app.protocol.C1Protocol
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeoutException

private const val TAG = "C1Ble"
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val HANDSHAKE_TIMEOUT_MS = 10_000L
private const val OPERATION_TIMEOUT_MS = 8_000L

/** 80-byte AVC packet / 10ms of mono 16kHz audio — see downloadRecording() doc comment for the ground truth this is measured from. */
private const val AVC_BYTES_PER_MS = 8L

/** Floor for the download hard-timeout ceiling when a session's reported duration is 0/unknown. */
private const val MIN_DOWNLOAD_TIMEOUT_MS = 60_000L

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
    private var syncTimeCharacteristic: BluetoothGattCharacteristic? = null

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    // The GATT connection only allows one outstanding operation at a time;
    // callers of readXxx()/readStorage() must await each before starting the
    // next. These two fields track whichever single operation is in flight.
    private var pendingRead: CancellableContinuation<Result<ByteArray>>? = null
    private var pendingIndicate: CancellableContinuation<Result<ByteArray>>? = null
    private var pendingIndicateExpectedOpcodes: Set<Int> = emptySet()
    private var pendingDescriptorWrite: CancellableContinuation<Boolean>? = null
    private var sessionStreamChannel: Channel<ByteArray>? = null
    private var fileStreamChannel: Channel<ByteArray>? = null
    private var downloadB001Channel: Channel<ByteArray>? = null
    private var downloadTailChannel: Channel<ByteArray>? = null
    private var pendingSyncTimeWrite: CancellableContinuation<Unit>? = null

    // Only used to sequence the post-handshake sync-time write strictly
    // before publishing Ready (see onCharacteristicChanged) — not a general
    // background-work scope.
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Capability-test only (attemptDownload): B001 carries raw, un-framed
    // file bytes via notify — collected here while a download is in flight.
    private var collectingDownloadData = false
    private val downloadBuffer = ByteArrayOutputStream()

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
            syncTimeCharacteristic = byUuid[C1Protocol.Characteristic.SYNC_TIME]
            startHandshake(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != C1Protocol.Descriptor.CLIENT_CHARACTERISTIC_CONFIG) return
            when (descriptor.characteristic?.uuid) {
                C1Protocol.Characteristic.CMD_INDICATE -> {
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
                else -> {
                    // Any other CCCD write (currently: B001 notify subscribe for attemptDownload) is awaited via pendingDescriptorWrite.
                    val cont = pendingDescriptorWrite
                    pendingDescriptorWrite = null
                    cont?.resume(status == BluetoothGatt.GATT_SUCCESS, onCancellation = null)
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == C1Protocol.Characteristic.SYNC_TIME) {
                Log.i(TAG, "syncDeviceTime write status=$status")
                val cont = pendingSyncTimeWrite
                pendingSyncTimeWrite = null
                cont?.resume(Unit, onCancellation = null)
                return
            }
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
            if (characteristic.uuid == C1Protocol.Characteristic.FILE_NOTIFY) {
                val data = characteristic.value ?: ByteArray(0)
                downloadB001Channel?.trySend(data)
                if (collectingDownloadData) {
                    downloadBuffer.write(data)
                }
                return
            }
            if (characteristic.uuid != C1Protocol.Characteristic.CMD_INDICATE) return
            val data = characteristic.value ?: ByteArray(0)
            when (_state.value) {
                C1ClientState.PerformingHandshake -> {
                    Log.i(TAG, "handshake ack, opcode=${C1Protocol.parseResponseOpcode(data)}")
                    clearTimeout()
                    // Await the SYNC_TIME write's own onCharacteristicWrite
                    // before publishing Ready: the GATT connection allows
                    // only one outstanding operation, and the repository
                    // starts its own reads (SN/firmware/battery/state/
                    // storage) the instant it observes Ready. Firing that
                    // write and flipping to Ready in the same breath (as
                    // this used to) let the two race, which could leave the
                    // native GATT operation queue wedged for the rest of the
                    // connection — every subsequent read failing, including
                    // manual retries. Sequencing this first costs one BLE
                    // round trip (tens of ms), never Ready itself.
                    internalScope.launch {
                        syncDeviceTimeAndAwait(g)
                        _state.value = C1ClientState.Ready
                    }
                }
                C1ClientState.Ready -> {
                    val opcode = C1Protocol.parseResponseOpcode(data)
                    if (opcode == C1Protocol.ResponseOpcode.GET_SESSIONS_CONFIRM) {
                        val streamCh = sessionStreamChannel
                        if (streamCh != null) {
                            streamCh.trySend(data)
                            return
                        }
                    }
                    if (opcode == C1Protocol.ResponseOpcode.GET_FILES_CONFIRM) {
                        val fileCh = fileStreamChannel
                        if (fileCh != null) {
                            fileCh.trySend(data)
                            return
                        }
                    }
                    if (opcode == C1Protocol.ResponseOpcode.FILE_TAIL) {
                        downloadTailChannel?.trySend(data)
                    }
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

    override suspend fun getSessions(startSessionId: Long): Result<List<C1Protocol.SessionEntry>> {
        val g = gatt ?: return Result.failure(IllegalStateException("未连接"))
        val writeChar = cmdWriteCharacteristic ?: return Result.failure(IllegalStateException("cmdWrite 特征值不存在"))
        if (_state.value != C1ClientState.Ready) return Result.failure(IllegalStateException("设备未就绪"))
        if (pendingRead != null || pendingIndicate != null || sessionStreamChannel != null) {
            return Result.failure(IllegalStateException("已有操作进行中"))
        }

        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        sessionStreamChannel = channel
        val sessionMap = LinkedHashMap<Long, C1Protocol.SessionEntry>()

        return try {
            val frame = C1Protocol.buildGetSessionsFrame(startSessionId)
            writeChar.value = frame
            val started = try {
                g.writeCharacteristic(writeChar)
            } catch (e: SecurityException) {
                false
            }
            if (!started) {
                return Result.failure(IllegalStateException("会话列表请求发送失败"))
            }

            var isStreamEnded = false
            var isFirstPacket = true

            while (!isStreamEnded) {
                val timeoutMs = if (isFirstPacket) operationTimeoutMs else 2000L
                val packet = withTimeoutOrNull(timeoutMs) {
                    channel.receive()
                }
                if (packet == null) {
                    if (isFirstPacket) {
                        return Result.failure(TimeoutException("会话列表响应超时"))
                    } else {
                        Log.i(TAG, "getSessions: inter-packet timeout, ending stream with ${sessionMap.size} sessions")
                        break
                    }
                }
                isFirstPacket = false

                val (entries, hasSentinel) = C1Protocol.parseSessionFrame(packet)
                for (entry in entries) {
                    sessionMap[entry.sessionId] = entry
                }

                if (hasSentinel) {
                    Log.i(TAG, "getSessions: stream ended by sentinel (sessionId==0), total: ${sessionMap.size}")
                    isStreamEnded = true
                }
            }

            Result.success(sessionMap.values.toList())
        } finally {
            sessionStreamChannel = null
            channel.close()
        }
    }

    /**
     * Writes the phone's clock to D007 and waits for its own
     * onCharacteristicWrite before returning — see the call site's comment
     * on why this must not race the reads that follow Ready. Never fails the
     * connection: if D007 is missing, the write fails to start, or it times
     * out, this just logs and returns, so a sync-time hiccup never blocks
     * reaching Ready.
     */
    @Suppress("DEPRECATION")
    private suspend fun syncDeviceTimeAndAwait(g: BluetoothGatt) {
        val syncChar = syncTimeCharacteristic ?: return
        val payload = C1Protocol.buildSyncTimePayload()

        val completed = withTimeoutOrNull(operationTimeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                pendingSyncTimeWrite = cont
                syncChar.value = payload
                syncChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val started = try {
                    g.writeCharacteristic(syncChar)
                } catch (e: SecurityException) {
                    false
                }
                if (!started) {
                    pendingSyncTimeWrite = null
                    cont.resume(Unit, onCancellation = null)
                }
                cont.invokeOnCancellation { pendingSyncTimeWrite = null }
            }
        }
        if (completed == null) {
            pendingSyncTimeWrite = null
            Log.w(TAG, "syncDeviceTime timed out waiting for write ack — proceeding to Ready anyway")
        } else {
            Log.i(TAG, "syncDeviceTime: sent timestamp ${System.currentTimeMillis() / 1000L}, acked")
        }
    }

    override suspend fun startRealtime(recordType: Int): Result<C1Protocol.StartConfirm> =
        sendCommandAndAwaitIndicate(
            frame = C1Protocol.buildStartRealtimeFrame(recordType),
            expectedOpcodes = setOf(C1Protocol.ResponseOpcode.START_CNF),
            label = "开始录音",
        ).mapCatching { C1Protocol.parseStartConfirm(it) ?: error("开始录音响应格式异常") }

    override suspend fun pauseRecord(): Result<C1Protocol.RecordStatusEvent> =
        sendCommandAndAwaitIndicate(
            frame = C1Protocol.buildPauseRecordFrame(),
            expectedOpcodes = setOf(C1Protocol.ResponseOpcode.PAUSE_IND),
            label = "暂停录音",
        ).mapCatching { C1Protocol.parseRecordStatusEvent(it) ?: error("暂停响应格式异常") }

    override suspend fun stopRecord(): Result<C1Protocol.RecordStatusEvent> =
        sendCommandAndAwaitIndicate(
            frame = C1Protocol.buildStopRecordFrame(),
            expectedOpcodes = setOf(C1Protocol.ResponseOpcode.STOP_IND),
            label = "停止录音",
        ).mapCatching { C1Protocol.parseRecordStatusEvent(it) ?: error("停止响应格式异常") }

    override suspend fun getFiles(sessionId: Long, recordType: Int): Result<List<C1Protocol.FileEntry>> {
        val g = gatt ?: return Result.failure(IllegalStateException("未连接"))
        val writeChar = cmdWriteCharacteristic ?: return Result.failure(IllegalStateException("cmdWrite 特征值不存在"))
        if (_state.value != C1ClientState.Ready) return Result.failure(IllegalStateException("设备未就绪"))
        if (pendingRead != null || pendingIndicate != null || fileStreamChannel != null) {
            return Result.failure(IllegalStateException("已有操作进行中"))
        }

        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        fileStreamChannel = channel
        val fileMap = LinkedHashMap<Int, C1Protocol.FileEntry>()

        return try {
            val frame = C1Protocol.buildGetFilesFrame(sessionId, recordType)
            writeChar.value = frame
            val started = try {
                g.writeCharacteristic(writeChar)
            } catch (e: SecurityException) {
                false
            }
            if (!started) {
                return Result.failure(IllegalStateException("文件列表请求发送失败"))
            }

            var isStreamEnded = false
            var isFirstPacket = true

            while (!isStreamEnded) {
                val timeoutMs = if (isFirstPacket) operationTimeoutMs else 2000L
                val packet = withTimeoutOrNull(timeoutMs) {
                    channel.receive()
                }
                if (packet == null) {
                    if (isFirstPacket) {
                        return Result.failure(TimeoutException("文件列表响应超时"))
                    } else {
                        Log.i(TAG, "getFiles: inter-packet timeout, ending stream with ${fileMap.size} files")
                        break
                    }
                }
                isFirstPacket = false

                val (entries, hasSentinel) = C1Protocol.parseFileFrame(packet)
                for (entry in entries) {
                    fileMap[entry.fileId] = entry
                }

                if (hasSentinel) {
                    Log.i(TAG, "getFiles: stream ended by sentinel (fileId==0), total: ${fileMap.size}")
                    isStreamEnded = true
                }
            }

            Result.success(fileMap.values.toList())
        } finally {
            fileStreamChannel = null
            channel.close()
        }
    }

    /** One item arriving while a download is in flight — either more raw audio or the terminating FILE_TAIL. */
    private sealed class DownloadEvent {
        data class Data(val bytes: ByteArray) : DownloadEvent()
        data class Tail(val bytes: ByteArray) : DownloadEvent()
    }

    override suspend fun downloadRecording(
        sessionId: Long,
        fileId: Int,
        durationMs: Long,
        destinationDir: File,
        onProgress: ((BleDownloadProgress) -> Unit)?,
        onStage: ((DownloadStage) -> Unit)?,
    ): Result<BleDownloadResult> {
        val g = gatt ?: return Result.failure(IllegalStateException("未连接"))
        if (_state.value != C1ClientState.Ready) return Result.failure(IllegalStateException("设备未就绪"))
        if (pendingRead != null || pendingIndicate != null || downloadB001Channel != null) {
            return Result.failure(IllegalStateException("已有操作进行中"))
        }

        val fileNotifyChar = g.services.flatMap { it.characteristics }
            .firstOrNull { it.uuid == C1Protocol.Characteristic.FILE_NOTIFY }
            ?: return Result.failure(IllegalStateException("设备缺少 B001 特征值"))

        subscribeNotify(g, fileNotifyChar, "B001").onFailure { return Result.failure(it) }

        val b001Ch = Channel<ByteArray>(Channel.UNLIMITED)
        val tailCh = Channel<ByteArray>(1)
        downloadB001Channel = b001Ch
        downloadTailChannel = tailCh

        // B001 notifications ARE header-framed: [3-byte LE seq][1-byte len][payload].
        // Ground truth: com/sogou/teemo/translatepen/manager/StickWorker.java
        // handleData() in the decompiled vendor APK —
        //   seq = ByteUtil.toInt(buffer[0:3]); len = ByteUtil.toInt(buffer[3:4])
        //   if (seq == lastIndex) return  // duplicate, drop
        //   payload = buffer[4 : 4+len]
        // seq == 0 is a raw-stream end-of-data sentinel (StickWorker.doBuffer's
        // "i == 0" branch) — it stops the B001 stream but is NOT by itself a
        // success signal; completion still waits on FILE_TAIL below. An
        // earlier version of this method treated B001 as headerless raw
        // bytes, which fed the wrong data into both the CRC and the decoder —
        // that was wrong; this is back to matching the vendor exactly.
        val rawAvcStream = ByteArrayOutputStream()
        var packetCount = 0
        var duplicateCount = 0
        var gapPacketsMissing = 0
        var lastSeq = -1
        var minSeq = Int.MAX_VALUE
        var maxSeq = Int.MIN_VALUE
        var firstPacketHex: String? = null
        var lastPacketHex: String? = null
        var tail: C1Protocol.FileTail? = null

        // Expected AVC byte count, purely for the progress percentage — not
        // used to decide when the download is done. Ground truth from a real
        // device file (RECORD/20260919/10_44_36.{WAV,AVC}, mono 16kHz/16bit):
        // 505760 AVC bytes for a 63220ms recording = exactly 8 bytes/ms
        // (80-byte packet / 10ms — PenTransform.getFrame() for this SN prefix
        // is 10, not the 20 previously assumed).
        val expectedAvcBytes = if (durationMs > 0) durationMs * AVC_BYTES_PER_MS else 0L

        // Ceiling only to abandon a genuinely dead connection — not a normal
        // completion path. Reaching it without a FILE_TAIL is always reported
        // as a failed/incomplete download, never as success.
        val hardTimeoutMs = if (durationMs > 0) maxOf(MIN_DOWNLOAD_TIMEOUT_MS, durationMs * 2 + 30_000L) else MIN_DOWNLOAD_TIMEOUT_MS
        val deadlineAt = System.currentTimeMillis() + hardTimeoutMs

        return try {
            val headerResult = sendCommandAndAwaitIndicate(
                frame = C1Protocol.buildDownloadFrame(sessionId, fileId, start = 0, end = 0, recordType = C1Protocol.RecordType.COMMON),
                expectedOpcodes = setOf(C1Protocol.ResponseOpcode.FILE_HEADER),
                label = "下载-HEADER",
            )
            val headerData = headerResult.getOrNull()
                ?: return Result.failure(headerResult.exceptionOrNull() ?: IllegalStateException("等待 HEADER 失败"))

            val headerOk = C1Protocol.parseFileHeaderOk(headerData)
            if (headerOk != true) {
                val errCode = if (headerData.size > 2) headerData[2].toInt() else -1
                return Result.failure(IllegalStateException("设备拒绝下载请求 (HEADER code=$errCode)"))
            }

            Log.i(TAG, "DOWNLOAD_FILE header OK, waiting for B001 data + FILE_TAIL (hard timeout ${hardTimeoutMs}ms)")

            // Keep waiting for EITHER more data or the tail, however long that
            // takes, until the hard ceiling — no "N seconds of silence = done".
            while (tail == null) {
                val remaining = deadlineAt - System.currentTimeMillis()
                if (remaining <= 0) {
                    return Result.failure(
                        TimeoutException("下载超时：已收到 $packetCount 个数据包 / ${rawAvcStream.size()} 字节，但未收到 FILE_TAIL，下载未完成"),
                    )
                }

                val event = withTimeoutOrNull(remaining) {
                    select<DownloadEvent> {
                        tailCh.onReceive { DownloadEvent.Tail(it) }
                        b001Ch.onReceive { DownloadEvent.Data(it) }
                    }
                } ?: return Result.failure(
                    TimeoutException("下载超时：已收到 $packetCount 个数据包 / ${rawAvcStream.size()} 字节，但未收到 FILE_TAIL，下载未完成"),
                )

                when (event) {
                    is DownloadEvent.Tail -> {
                        tail = C1Protocol.parseFileTail(event.bytes)
                        Log.i(TAG, "FILE_TAIL received: eod=${tail?.eod}, crc=0x${tail?.crc16?.toString(16)}")
                    }
                    is DownloadEvent.Data -> {
                        val raw = event.bytes
                        val parsed = C1Protocol.parseB001Packet(raw)
                        if (parsed == null) {
                            Log.w(TAG, "B001 packet malformed (${raw.size} bytes, declared len ${if (raw.size > 3) raw[3].toInt() and 0xFF else -1}), dropped")
                        } else {
                            val (seq, payload) = parsed
                            val hex = raw.take(12).joinToString(" ") { "%02x".format(it) }
                            if (firstPacketHex == null) {
                                firstPacketHex = hex
                                Log.i(TAG, "B001 first packet: seq=$seq len=${payload.size} bytes=${raw.size} raw[0:12]=$hex")
                            }
                            lastPacketHex = hex

                            if (seq == 0) {
                                // End-of-raw-stream sentinel (vendor
                                // StickWorker.doBuffer's seq==0 branch) — the
                                // B001 side is done, but success is still
                                // decided by FILE_TAIL/CRC below, not this.
                                Log.i(TAG, "B001 sentinel seq=0 after $packetCount packets / ${rawAvcStream.size()} bytes — raw stream done, still waiting for FILE_TAIL if not received yet")
                            } else if (seq == lastSeq) {
                                duplicateCount++
                                Log.w(TAG, "B001 duplicate seq=$seq ignored")
                            } else {
                                if (payload.size != 80) {
                                    Log.w(TAG, "B001 seq=$seq has non-standard len=${payload.size} (expected 80)")
                                }
                                if (lastSeq >= 0 && seq != lastSeq + 1) {
                                    val missing = seq - lastSeq - 1
                                    if (missing > 0) {
                                        gapPacketsMissing += missing
                                        Log.w(TAG, "B001 sequence gap: expected ${lastSeq + 1}, got $seq ($missing packet(s) missing)")
                                    } else {
                                        Log.w(TAG, "B001 out-of-order: expected ${lastSeq + 1}, got $seq")
                                    }
                                }
                                lastSeq = seq
                                minSeq = minOf(minSeq, seq)
                                maxSeq = maxOf(maxSeq, seq)

                                rawAvcStream.write(payload)
                                packetCount++
                                val bytesReceived = rawAvcStream.size().toLong()
                                val percent = if (expectedAvcBytes > 0) {
                                    (bytesReceived * 100 / expectedAvcBytes).toInt().coerceIn(0, 99)
                                } else 0
                                onProgress?.invoke(
                                    BleDownloadProgress(
                                        sessionId = sessionId,
                                        fileId = fileId,
                                        bytesReceived = bytesReceived,
                                        totalBytesExpected = expectedAvcBytes,
                                        packetCount = packetCount,
                                        percent = percent,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            val finalTail = tail // smart-cast to non-null: the loop above only exits once tail != null
            val rawAvcBytes = rawAvcStream.toByteArray()
            if (rawAvcBytes.isEmpty()) {
                return Result.failure(IllegalStateException("未收到任何音频数据"))
            }

            onStage?.invoke(DownloadStage.VERIFYING)
            val computedCrc = C1Protocol.Crc16.calc(rawAvcBytes)
            // Device firmware V127 sends 0xFFFF as an uncomputed placeholder in FILE_TAIL.
            // When 0xFFFF is returned, integrity is guaranteed by gapPacketsMissing == 0 (strict packet sequence check).
            // When a concrete CRC is provided (!= 0xFFFF), it must match computedCrc.
            val crcMatches = (finalTail.crc16 == 0xFFFF) || (computedCrc == finalTail.crc16)

            Log.i(
                TAG,
                "Download stream ended: packets=$packetCount bytes=${rawAvcBytes.size} " +
                    "seqRange=[$minSeq,$maxSeq] duplicates=$duplicateCount missingFromGaps=$gapPacketsMissing " +
                    "firstPacket=$firstPacketHex lastPacket=$lastPacketHex " +
                    "expectedBytes=$expectedAvcBytes tailCRC=0x${finalTail.crc16.toString(16)} " +
                    "calcCRC=0x${computedCrc.toString(16)} eod=${finalTail.eod} match=$crcMatches",
            )

            if (gapPacketsMissing > 0) {
                return Result.failure(
                    IOException(
                        "下载数据包丢失：共缺失 $gapPacketsMissing 个数据包 (实际收到 $packetCount 包)，数据不完整，未生成文件",
                    ),
                )
            }

            if (!crcMatches) {
                return Result.failure(
                    IOException(
                        "CRC 校验失败 (设备=0x${finalTail.crc16.toString(16)}, 本地计算=0x${computedCrc.toString(16)}, " +
                            "包数=$packetCount, 缺包=$gapPacketsMissing, 重复=$duplicateCount)，数据可能损坏，未生成文件",
                    ),
                )
            }

            onProgress?.invoke(
                BleDownloadProgress(sessionId, fileId, rawAvcBytes.size.toLong(), rawAvcBytes.size.toLong(), packetCount, percent = 100),
            )

            destinationDir.mkdirs()
            val avcFile = File(destinationDir, "session_${sessionId}_${fileId}.avc")
            avcFile.writeBytes(rawAvcBytes)

            // C1AudioDecoder always drives the native decoder in stereo (see
            // its own doc comment for why — confirmed by instrumented
            // testing, not a guess) and downmixes to mono to match the
            // device's own on-device WAV format. No fallback on failure — a
            // decode failure is reported honestly, not silently retried
            // with a guessed parameter.
            onStage?.invoke(DownloadStage.DECODING)
            val wavFile = File(destinationDir, "session_${sessionId}_${fileId}.wav")
            val decoder = C1AudioDecoder()
            if (!decoder.decodeAvcFileToWav(avcFile, wavFile)) {
                return Result.failure(IOException("CRC 校验通过，但音频解码失败 (CELT)"))
            }

            Result.success(
                BleDownloadResult(
                    sessionId = sessionId,
                    fileId = fileId,
                    rawAvcFile = avcFile,
                    wavFile = wavFile,
                    bytesReceived = rawAvcBytes.size.toLong(),
                    packetCount = packetCount,
                    crc16 = finalTail.crc16,
                    crcVerified = true,
                )
            )
        } finally {
            downloadB001Channel = null
            downloadTailChannel = null
            b001Ch.close()
            tailCh.close()
            sendStopDownloadBestEffort(g)
        }
    }

    override suspend fun attemptDownload(sessionId: Long, fileId: Int, start: Long, end: Long, recordType: Int): Result<DownloadAttempt> {
        val g = gatt ?: return Result.failure(IllegalStateException("未连接"))
        if (_state.value != C1ClientState.Ready) return Result.failure(IllegalStateException("设备未就绪"))
        if (pendingRead != null || pendingIndicate != null) {
            return Result.failure(IllegalStateException("已有操作进行中"))
        }

        val fileNotifyChar = g.services.flatMap { it.characteristics }
            .firstOrNull { it.uuid == C1Protocol.Characteristic.FILE_NOTIFY }
            ?: return Result.failure(IllegalStateException("设备缺少 B001 特征值"))

        subscribeNotify(g, fileNotifyChar, "B001").onFailure { return Result.failure(it) }

        downloadBuffer.reset()
        collectingDownloadData = true
        try {
            val headerResult = sendCommandAndAwaitIndicate(
                frame = C1Protocol.buildDownloadFrame(sessionId, fileId, start, end, recordType),
                expectedOpcodes = setOf(C1Protocol.ResponseOpcode.FILE_HEADER),
                label = "下载-HEADER",
            )
            val headerOk = headerResult.getOrNull()?.let { C1Protocol.parseFileHeaderOk(it) }
            if (headerResult.isFailure || headerOk != true) {
                return Result.success(DownloadAttempt(headerOk = headerOk, bytesReceived = 0, tail = null, computedCrc = null))
            }

            val tailResult = awaitIndicate(setOf(C1Protocol.ResponseOpcode.FILE_TAIL), "下载-TAIL")
            val tail = tailResult.getOrNull()?.let { C1Protocol.parseFileTail(it) }
            val collected = downloadBuffer.toByteArray()
            val computedCrc = if (tail != null) C1Protocol.Crc16.calc(collected) else null

            return Result.success(DownloadAttempt(headerOk = true, bytesReceived = collected.size, tail = tail, computedCrc = computedCrc))
        } finally {
            collectingDownloadData = false
            sendStopDownloadBestEffort(g)
        }
    }

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

    /** Waits for the next matching indicate without writing anything first — for a second/later response to a single earlier write (e.g. TAIL after HEADER). */
    @Suppress("DEPRECATION")
    private suspend fun awaitIndicate(expectedOpcodes: Set<Int>, label: String): Result<ByteArray> {
        if (_state.value != C1ClientState.Ready) return Result.failure(IllegalStateException("设备未就绪"))
        if (pendingIndicate != null) return Result.failure(IllegalStateException("已有操作进行中"))

        val result = withTimeoutOrNull(operationTimeoutMs) {
            suspendCancellableCoroutine { cont ->
                pendingIndicate = cont
                pendingIndicateExpectedOpcodes = expectedOpcodes
                cont.invokeOnCancellation { pendingIndicate = null }
            }
        }
        if (result == null) {
            pendingIndicate = null
            return Result.failure(TimeoutException("$label 超时"))
        }
        return result
    }

    @Suppress("DEPRECATION")
    private suspend fun subscribeNotify(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, label: String): Result<Unit> {
        val result = withTimeoutOrNull(operationTimeoutMs) {
            suspendCancellableCoroutine { cont ->
                pendingDescriptorWrite = cont
                try {
                    val cccd = characteristic.getDescriptor(C1Protocol.Descriptor.CLIENT_CHARACTERISTIC_CONFIG)
                    if (!g.setCharacteristicNotification(characteristic, true) || cccd == null) {
                        pendingDescriptorWrite = null
                        cont.resume(false, onCancellation = null)
                        return@suspendCancellableCoroutine
                    }
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (!g.writeDescriptor(cccd)) {
                        pendingDescriptorWrite = null
                        cont.resume(false, onCancellation = null)
                    }
                } catch (e: SecurityException) {
                    pendingDescriptorWrite = null
                    cont.resume(false, onCancellation = null)
                }
                cont.invokeOnCancellation { pendingDescriptorWrite = null }
            }
        }
        return if (result == true) Result.success(Unit) else Result.failure(IOException("$label 订阅失败或超时"))
    }

    @Suppress("DEPRECATION")
    private fun sendStopDownloadBestEffort(g: BluetoothGatt) {
        val writeChar = cmdWriteCharacteristic ?: return
        try {
            writeChar.value = C1Protocol.buildStopDownloadFrame()
            g.writeCharacteristic(writeChar)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopDownload write denied", e)
        }
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
        syncTimeCharacteristic = null

        sessionStreamChannel?.close()
        sessionStreamChannel = null
        fileStreamChannel?.close()
        fileStreamChannel = null
        downloadB001Channel?.close()
        downloadB001Channel = null
        downloadTailChannel?.close()
        downloadTailChannel = null

        // GATT callbacks can run on a different thread than whoever called
        // disconnect(), so a pending continuation may already have been
        // resumed by the time we get here — resume is not safe to call twice.
        val readCont = pendingRead
        pendingRead = null
        readCont?.let { safeResume(it, Result.failure(IOException("连接已断开"))) }

        val indicateCont = pendingIndicate
        pendingIndicate = null
        indicateCont?.let { safeResume(it, Result.failure(IOException("连接已断开"))) }

        val descriptorCont = pendingDescriptorWrite
        pendingDescriptorWrite = null
        try {
            descriptorCont?.resume(false) { _, _, _ -> }
        } catch (e: IllegalStateException) {
            // Already resumed/cancelled from the GATT callback thread — fine, ignore.
        }

        val syncTimeCont = pendingSyncTimeWrite
        pendingSyncTimeWrite = null
        try {
            syncTimeCont?.resume(Unit) { _, _, _ -> }
        } catch (e: IllegalStateException) {
            // Already resumed/cancelled from the GATT callback thread — fine, ignore.
        }

        collectingDownloadData = false
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
