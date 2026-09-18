package com.c1recorder.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.c1recorder.app.data.C1Repository
import kotlinx.coroutines.launch

class MainViewModel(private val repository: C1Repository) : ViewModel() {
    val connectionState = repository.connectionState
    val device = repository.device
    val sessions = repository.sessions

    fun connect(address: String) {
        viewModelScope.launch { repository.connect(address) }
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun refreshSessions() {
        viewModelScope.launch { repository.refreshSessions() }
    }

    class Factory(private val repository: C1Repository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository) as T
    }
}
