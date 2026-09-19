package com.c1recorder.app.ble

import com.c1recorder.app.protocol.C1Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Test/dev double for C1BleClient. connect()/disconnect() only move to the
 * states a real GATT stack could reach on its own (Connecting, Disconnected);
 * the rest of the state machine is driven explicitly via simulate* calls so
 * tests can assert each transition, mirroring the real callback sequence
 * connect -> onConnectionStateChange(CONNECTED) -> onServicesDiscovered ->
 * onDescriptorWrite (CCCD) -> onCharacteristicChanged (handshake ack).
 */
class FakeC1BleClient : C1BleClient {
    private val _state = MutableStateFlow<C1ClientState>(C1ClientState.Disconnected)
    override val state: StateFlow<C1ClientState> = _state.asStateFlow()

    var lastConnectedAddress: String? = null
        private set

    override fun connect(address: String) {
        lastConnectedAddress = address
        _state.value = C1ClientState.Connecting
    }

    override fun disconnect() {
        _state.value = C1ClientState.Disconnected
    }

    fun simulateConnected() {
        if (_state.value != C1ClientState.Connecting) return
        _state.value = C1ClientState.DiscoveringServices
    }

    fun simulateServicesDiscovered(discoveredCharacteristics: Set<UUID>) {
        if (_state.value != C1ClientState.DiscoveringServices) return
        val missing = RequiredCharacteristics.findMissing(discoveredCharacteristics)
        _state.value = if (missing.isEmpty()) {
            C1ClientState.PerformingHandshake
        } else {
            C1ClientState.MissingCharacteristics(missing)
        }
    }

    fun simulateHandshakeAcked() {
        if (_state.value != C1ClientState.PerformingHandshake) return
        _state.value = C1ClientState.Ready
    }

    fun simulateConnectionFailed(reason: String) {
        _state.value = C1ClientState.ConnectionFailed(reason)
    }

    fun simulateDisconnected() {
        _state.value = C1ClientState.Disconnected
    }

    // Fixed, overridable fake device info — lets Compose UI be built and
    // manually exercised without a real C1 connected. Defaults mirror the
    // real device's values from docs/FINAL-investigation-summary.md.
    var fakeSerialNumber: Result<String> = Result.success("5200000000000000")
    var fakeFirmwareVersion: Result<String> = Result.success("V127")
    var fakeBatteryLevel: Result<Int> = Result.success(80)
    var fakeState: Result<Int> = Result.success(C1Protocol.StateValue.IDLE)
    var fakeStorage: Result<C1Protocol.StorageInfo> =
        Result.success(C1Protocol.StorageInfo(totalKB = 15_258_988L, freeKB = 1_048_576L, bytesPerSecond = 0L, isFull = false))

    override suspend fun readSerialNumber(): Result<String> = fakeSerialNumber
    override suspend fun readFirmwareVersion(): Result<String> = fakeFirmwareVersion
    override suspend fun readBatteryLevel(): Result<Int> = fakeBatteryLevel
    override suspend fun readState(): Result<Int> = fakeState
    override suspend fun readStorage(): Result<C1Protocol.StorageInfo> = fakeStorage

    // Keyed by the startSessionId a page was requested with, so tests can
    // script multi-page pagination the same way the real device paginates
    // (see docs/FINAL-investigation-summary.md and DefaultC1Repository).
    // Any startSessionId not present here returns an empty page.
    var fakeSessionPages: Map<Long, Result<List<C1Protocol.SessionEntry>>> = emptyMap()

    override suspend fun getSessions(startSessionId: Long): Result<List<C1Protocol.SessionEntry>> =
        fakeSessionPages[startSessionId] ?: Result.success(emptyList())

    // --- C1 Hardware Capability Test fakes ---
    var fakeStartRealtime: Result<C1Protocol.StartConfirm> = Result.success(C1Protocol.StartConfirm(sessionId = 0x1L, field2 = 0, recordType = C1Protocol.RecordType.COMMON))
    var fakePauseRecord: Result<C1Protocol.RecordStatusEvent> = Result.success(C1Protocol.RecordStatusEvent(sessionId = 0x1L, field2 = 0))
    var fakeStopRecord: Result<C1Protocol.RecordStatusEvent> = Result.success(C1Protocol.RecordStatusEvent(sessionId = 0x1L, field2 = 0))
    var fakeGetFiles: Result<List<C1Protocol.FileEntry>> = Result.success(emptyList())
    var fakeAttemptDownload: Result<DownloadAttempt> = Result.success(DownloadAttempt(headerOk = null, bytesReceived = 0, tail = null, computedCrc = null))

    override suspend fun startRealtime(recordType: Int): Result<C1Protocol.StartConfirm> = fakeStartRealtime
    override suspend fun pauseRecord(): Result<C1Protocol.RecordStatusEvent> = fakePauseRecord
    override suspend fun stopRecord(): Result<C1Protocol.RecordStatusEvent> = fakeStopRecord
    override suspend fun getFiles(sessionId: Long, recordType: Int): Result<List<C1Protocol.FileEntry>> = fakeGetFiles
    override suspend fun attemptDownload(sessionId: Long, fileId: Int, start: Long, end: Long, recordType: Int): Result<DownloadAttempt> = fakeAttemptDownload
}
