package com.c1recorder.app.data

import com.c1recorder.app.ble.C1BleClient
import com.c1recorder.app.ble.C1ClientState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
}

/**
 * connectionState is a direct passthrough of the underlying client's state —
 * there is nothing to translate yet. device/sessions stay empty placeholders
 * until Phase 5 (device info) and Phase 6 (getSessions) read them over the
 * connection this phase establishes.
 */
class DefaultC1Repository(private val client: C1BleClient) : C1Repository {
    override val connectionState: StateFlow<C1ClientState> = client.state

    private val _device = MutableStateFlow<C1Device?>(null)
    override val device: StateFlow<C1Device?> = _device.asStateFlow()

    private val _sessions = MutableStateFlow<List<RecordingSession>>(emptyList())
    override val sessions: StateFlow<List<RecordingSession>> = _sessions.asStateFlow()

    override fun connect(address: String) {
        client.connect(address)
    }

    override fun disconnect() {
        client.disconnect()
    }

    override suspend fun refreshSessions() {
        // Intentionally unimplemented: getSessions lands in Phase 6.
    }
}
