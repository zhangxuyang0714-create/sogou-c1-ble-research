package com.c1recorder.app.data

import com.c1recorder.app.ble.C1BleClient
import com.c1recorder.app.ble.C1ClientState
import com.c1recorder.app.protocol.C1Protocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
 * connectionState is a direct passthrough of the underlying client's state.
 * Once the client reaches Ready, this repository automatically reads SN,
 * firmware, battery, state and storage and publishes them as a C1Device —
 * that's the only orchestration needed since every read is independently
 * safe to call once, in sequence (the GATT connection allows only one
 * outstanding operation at a time).
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

    private var connectedAddress: String? = null

    init {
        scope.launch {
            client.state.collect { state ->
                when (state) {
                    C1ClientState.Ready -> refreshDeviceInfo()
                    C1ClientState.Disconnected -> _device.value = null
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
        // Intentionally unimplemented: getSessions lands in Phase 6.
    }

    private suspend fun refreshDeviceInfo() {
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
}
