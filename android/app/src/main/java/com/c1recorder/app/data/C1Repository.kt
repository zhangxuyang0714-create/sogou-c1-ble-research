package com.c1recorder.app.data

import kotlinx.coroutines.flow.StateFlow

/**
 * What the UI/ViewModel layer is allowed to know about the C1 device.
 * Implementations (real BLE client, fake client) live below this interface;
 * Compose code must only ever talk to this, never to BluetoothGatt directly.
 */
interface C1Repository {
    val connectionState: StateFlow<C1ConnectionState>
    val device: StateFlow<C1Device?>
    val sessions: StateFlow<List<RecordingSession>>

    suspend fun connect(address: String)
    fun disconnect()
    suspend fun refreshSessions()
}

/**
 * No-op placeholder used while the real BLE client (Phase 2-4) doesn't exist
 * yet. Lets the app skeleton compile and run before any BLE code is written.
 */
class StubC1Repository : C1Repository {
    override val connectionState = kotlinx.coroutines.flow.MutableStateFlow(C1ConnectionState.DISCONNECTED)
    override val device = kotlinx.coroutines.flow.MutableStateFlow<C1Device?>(null)
    override val sessions = kotlinx.coroutines.flow.MutableStateFlow<List<RecordingSession>>(emptyList())

    override suspend fun connect(address: String) {
        // Intentionally unimplemented: BLE connection lands in Phase 3.
    }

    override fun disconnect() {
        // Intentionally unimplemented: BLE connection lands in Phase 3.
    }

    override suspend fun refreshSessions() {
        // Intentionally unimplemented: getSessions lands in Phase 6.
    }
}
