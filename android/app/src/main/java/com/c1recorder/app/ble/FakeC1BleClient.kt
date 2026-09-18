package com.c1recorder.app.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Test/dev double for C1BleClient. connect()/disconnect() only move to the
 * states a real GATT stack could reach on its own (Connecting, Disconnected);
 * the rest of the state machine is driven explicitly via simulate* calls so
 * tests can assert each transition, mirroring the real callback sequence
 * connect -> onConnectionStateChange(CONNECTED) -> onServicesDiscovered.
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
            C1ClientState.Connected
        } else {
            C1ClientState.MissingCharacteristics(missing)
        }
    }

    fun simulateConnectionFailed(reason: String) {
        _state.value = C1ClientState.ConnectionFailed(reason)
    }

    fun simulateDisconnected() {
        _state.value = C1ClientState.Disconnected
    }
}
