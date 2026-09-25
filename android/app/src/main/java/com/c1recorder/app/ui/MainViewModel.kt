package com.c1recorder.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.c1recorder.app.ble.C1BleClient
import com.c1recorder.app.ble.C1BleScanner
import com.c1recorder.app.data.C1Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: C1Repository,
    private val scanner: C1BleScanner,
    // Capability-test only (docs/ANDROID-HARDWARE-CAPABILITY-TEST.md) — the
    // confirmed v1 feature set (SN/battery/state/storage/sessions) goes
    // through repository, never through this raw client directly.
    private val bleClient: C1BleClient,
) : ViewModel() {
    val connectionState = repository.connectionState
    val device = repository.device
    val sessions = repository.sessions
    val downloadStates = repository.downloadStates

    private var mediaPlayer: android.media.MediaPlayer? = null
    private val _playingSessionId = MutableStateFlow<Long?>(null)
    val playingSessionId: StateFlow<Long?> = _playingSessionId.asStateFlow()

    val scanState = scanner.scanState
    val discoveredDevices = scanner.discoveredDevices

    fun downloadSession(session: com.c1recorder.app.data.RecordingSession, destinationDir: java.io.File) {
        viewModelScope.launch {
            repository.downloadSession(session, destinationDir)
        }
    }

    fun checkLocalFiles(destinationDir: java.io.File) {
        repository.checkLocalFiles(destinationDir)
    }

    fun togglePlayAudio(sessionId: Long, wavFile: java.io.File) {
        if (_playingSessionId.value == sessionId) {
            stopAudio()
            return
        }

        try {
            stopAudio()
            val player = android.media.MediaPlayer().apply {
                setDataSource(wavFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopAudio()
                }
                start()
            }
            mediaPlayer = player
            _playingSessionId.value = sessionId
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Playback error for session $sessionId", e)
            stopAudio()
        }
    }

    fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Ignored
        } finally {
            mediaPlayer = null
            _playingSessionId.value = null
        }
    }

    fun startScan() {
        scanner.startScan()
    }

    fun stopScan() {
        scanner.stopScan()
    }

    fun connect(address: String) {
        repository.connect(address)
    }

    fun disconnect() {
        repository.disconnect()
    }

    private val _isRefreshingSessions = MutableStateFlow(false)
    val isRefreshingSessions: StateFlow<Boolean> = _isRefreshingSessions.asStateFlow()

    fun refreshSessions() {
        if (_isRefreshingSessions.value) return
        viewModelScope.launch {
            _isRefreshingSessions.value = true
            try {
                repository.refreshSessions()
            } finally {
                _isRefreshingSessions.value = false
            }
        }
    }

    fun refreshDeviceInfo() {
        viewModelScope.launch { repository.refreshDeviceInfo() }
    }

    private val _capabilityTestLog = MutableStateFlow<List<String>>(emptyList())
    val capabilityTestLog: StateFlow<List<String>> = _capabilityTestLog.asStateFlow()

    private fun log(line: String) {
        _capabilityTestLog.value = _capabilityTestLog.value + line
    }

    fun clearCapabilityTestLog() {
        _capabilityTestLog.value = emptyList()
    }

    fun testStartRealtime() = runCapabilityTest("startRealtime(Common)") { bleClient.startRealtime() }

    fun testStopRecord() = runCapabilityTest("stopRecord()") { bleClient.stopRecord() }

    fun testGetFiles(sessionId: Long) = runCapabilityTest("getFiles(0x${sessionId.toString(16)})") { bleClient.getFiles(sessionId) }

    fun testAttemptDownload(sessionId: Long, fileId: Int, start: Long, end: Long) =
        runCapabilityTest("attemptDownload(0x${sessionId.toString(16)}, fileId=$fileId, $start..$end)") {
            bleClient.attemptDownload(sessionId, fileId, start, end)
        }

    private fun <T> runCapabilityTest(label: String, block: suspend () -> Result<T>) {
        viewModelScope.launch {
            log("→ $label")
            val result = block()
            result.fold(
                onSuccess = { log("← $label OK: $it") },
                onFailure = { log("← $label FAIL: ${it.message}") },
            )
        }
    }

    override fun onCleared() {
        stopAudio()
        scanner.stopScan()
        repository.disconnect()
    }

    class Factory(
        private val repository: C1Repository,
        private val scanner: C1BleScanner,
        private val bleClient: C1BleClient,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository, scanner, bleClient) as T
    }
}
