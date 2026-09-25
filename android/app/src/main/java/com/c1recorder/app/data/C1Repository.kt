package com.c1recorder.app.data

import com.c1recorder.app.ble.C1BleClient
import com.c1recorder.app.ble.C1ClientState
import com.c1recorder.app.ble.DownloadStage
import com.c1recorder.app.protocol.C1Protocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the UI/ViewModel layer is allowed to know about the C1 device.
 * Implementations sit on top of a C1BleClient (real or fake); Compose code
 * must only ever talk to this, never to BluetoothGatt directly.
 */
interface C1Repository {
    val connectionState: StateFlow<C1ClientState>
    val device: StateFlow<C1Device?>
    val sessions: StateFlow<List<RecordingSession>>

    fun connect(address: String)
    fun disconnect()
    suspend fun refreshSessions()

    /** Re-reads SN/firmware/battery/state/storage on demand — used by the capability test to check D005 before/after an experiment (docs/ANDROID-HARDWARE-CAPABILITY-TEST.md). */
    suspend fun refreshDeviceInfo()

    val downloadStates: StateFlow<Map<Long, SessionDownloadState>>
    suspend fun downloadSession(session: RecordingSession, destinationDir: java.io.File)
    fun checkLocalFiles(destinationDir: java.io.File)
}

/**
 * connectionState is a direct passthrough of the underlying client's state.
 * Once the client reaches Ready, this repository automatically reads SN,
 * firmware, battery, state and storage and publishes them as a C1Device —
 * that's the only orchestration needed since every read is independently
 * safe to call once, in sequence (the GATT connection allows only one
 * outstanding operation at a time). Session list pagination (refreshSessions)
 * is a separate, explicitly user-triggered operation — see MainScreen.
 */
class DefaultC1Repository(
    private val client: C1BleClient,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : C1Repository {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override val connectionState: StateFlow<C1ClientState> = client.state

    private val _device = MutableStateFlow<C1Device?>(null)
    override val device: StateFlow<C1Device?> = _device.asStateFlow()

    private val _sessions = MutableStateFlow<List<RecordingSession>>(emptyList())
    override val sessions: StateFlow<List<RecordingSession>> = _sessions.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<Long, SessionDownloadState>>(emptyMap())
    override val downloadStates: StateFlow<Map<Long, SessionDownloadState>> = _downloadStates.asStateFlow()

    private var connectedAddress: String? = null
    private val refreshMutex = Mutex()

    init {
        scope.launch {
            client.state.collect { state ->
                when (state) {
                    C1ClientState.Ready -> refreshDeviceInfo()
                    C1ClientState.Disconnected -> {
                        _device.value = null
                        _sessions.value = emptyList()
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun connect(address: String) {
        connectedAddress = address
        client.connect(address)
    }

    override fun disconnect() {
        client.disconnect()
    }

    override suspend fun refreshSessions() {
        if (client.state.value != C1ClientState.Ready) return

        refreshMutex.withLock {
            val entries = client.getSessions(0L).getOrNull() ?: return
            _sessions.value = entries.map {
                RecordingSession(
                    sessionId = it.sessionId,
                    durationMs = it.durationMs,
                    thirdFieldUnknown = it.thirdFieldUnknown,
                )
            }.sortedByDescending { it.sessionId }
        }
    }

    override suspend fun refreshDeviceInfo() {
        val address = connectedAddress ?: return
        val sn = client.readSerialNumber().getOrNull()
        val firmware = client.readFirmwareVersion().getOrNull()
        val battery = client.readBatteryLevel().getOrNull()
        val stateRaw = client.readState().getOrNull()
        val storage = client.readStorage().getOrNull()
        _device.value = C1Device(
            address = address,
            name = C1Protocol.Identity.DEVICE_NAME,
            sn = sn,
            firmware = firmware,
            batteryPercent = battery,
            stateRaw = stateRaw,
            totalStorageKB = storage?.totalKB,
            freeStorageKB = storage?.freeKB,
        )
    }

    override suspend fun downloadSession(session: RecordingSession, destinationDir: java.io.File) {
        if (client.state.value != C1ClientState.Ready) {
            _downloadStates.update { it + (session.sessionId to SessionDownloadState.Error(session.sessionId, "设备未连接或未就绪")) }
            return
        }

        _downloadStates.update { it + (session.sessionId to SessionDownloadState.FetchingFiles(session.sessionId)) }

        // 1. Fetch file list for this session
        val filesResult = client.getFiles(session.sessionId)
        val files = filesResult.getOrNull()
        if (filesResult.isFailure || files == null) {
            val err = filesResult.exceptionOrNull()?.message ?: "检索文件列表失败"
            _downloadStates.update { it + (session.sessionId to SessionDownloadState.Error(session.sessionId, err)) }
            return
        }

        val targetFile = files.firstOrNull { it.fileId > 0 && it.fileId != 0xFFFF }
        if (targetFile == null) {
            _downloadStates.update { it + (session.sessionId to SessionDownloadState.Error(session.sessionId, "未找到有效文件条目 (可能尚未在设备落盘)")) }
            return
        }

        _downloadStates.update {
            it + (session.sessionId to SessionDownloadState.Downloading(
                sessionId = session.sessionId,
                fileId = targetFile.fileId,
                bytesReceived = 0L,
                percent = 0,
            ))
        }

        // 2. Download and decode audio
        val durationMs = if (targetFile.size > 0) targetFile.size else session.durationMs
        val downloadResult = client.downloadRecording(
            sessionId = session.sessionId,
            fileId = targetFile.fileId,
            durationMs = durationMs,
            destinationDir = destinationDir,
            onProgress = { prog ->
                _downloadStates.update {
                    it + (session.sessionId to SessionDownloadState.Downloading(
                        sessionId = session.sessionId,
                        fileId = prog.fileId,
                        bytesReceived = prog.bytesReceived,
                        percent = prog.percent,
                    ))
                }
            },
            onStage = { stage ->
                val newState = when (stage) {
                    DownloadStage.VERIFYING -> SessionDownloadState.Verifying(session.sessionId)
                    DownloadStage.DECODING -> SessionDownloadState.Decoding(session.sessionId)
                }
                _downloadStates.update { it + (session.sessionId to newState) }
            },
        )

        downloadResult.fold(
            onSuccess = { res ->
                _downloadStates.update {
                    it + (session.sessionId to SessionDownloadState.Completed(
                        sessionId = session.sessionId,
                        fileId = res.fileId,
                        wavFile = res.wavFile,
                        avcFile = res.rawAvcFile,
                        durationMs = session.durationMs,
                        crcVerified = res.crcVerified,
                    ))
                }
            },
            onFailure = { err ->
                _downloadStates.update {
                    it + (session.sessionId to SessionDownloadState.Error(
                        sessionId = session.sessionId,
                        message = err.message ?: "下载失败",
                    ))
                }
            },
        )
    }

    override fun checkLocalFiles(destinationDir: java.io.File) {
        val sessionsList = _sessions.value
        val map = _downloadStates.value.toMutableMap()
        for (s in sessionsList) {
            val wav = java.io.File(destinationDir, "session_${s.sessionId}_1.wav")
            val avc = java.io.File(destinationDir, "session_${s.sessionId}_1.avc")
            if (wav.exists() && wav.length() > 44) {
                if (map[s.sessionId] !is SessionDownloadState.Downloading && map[s.sessionId] !is SessionDownloadState.FetchingFiles) {
                    map[s.sessionId] = SessionDownloadState.Completed(
                        sessionId = s.sessionId,
                        fileId = 1,
                        wavFile = wav,
                        avcFile = avc,
                        durationMs = s.durationMs,
                        crcVerified = true,
                    )
                }
            }
        }
        _downloadStates.value = map
    }
}
