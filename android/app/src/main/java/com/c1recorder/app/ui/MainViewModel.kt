package com.c1recorder.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.c1recorder.app.ble.C1BleScanner
import com.c1recorder.app.data.C1Repository
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: C1Repository,
    private val scanner: C1BleScanner,
) : ViewModel() {
    val connectionState = repository.connectionState
    val device = repository.device
    val sessions = repository.sessions

    val scanState = scanner.scanState
    val discoveredDevices = scanner.discoveredDevices

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

    fun refreshSessions() {
        viewModelScope.launch { repository.refreshSessions() }
    }

    override fun onCleared() {
        scanner.stopScan()
        repository.disconnect()
    }

    class Factory(
        private val repository: C1Repository,
        private val scanner: C1BleScanner,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository, scanner) as T
    }
}
